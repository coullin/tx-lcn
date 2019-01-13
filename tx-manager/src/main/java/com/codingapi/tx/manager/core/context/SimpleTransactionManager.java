package com.codingapi.tx.manager.core.context;

import com.codingapi.tx.client.spi.message.RpcClient;
import com.codingapi.tx.client.spi.message.dto.MessageDto;
import com.codingapi.tx.client.spi.message.exception.RpcException;
import com.codingapi.tx.client.spi.message.params.NotifyUnitParams;
import com.codingapi.tx.client.spi.message.util.MessageUtils;
import com.codingapi.tx.commons.exception.JoinGroupException;
import com.codingapi.tx.commons.exception.SerializerException;
import com.codingapi.tx.commons.util.Transactions;
import com.codingapi.tx.commons.util.serializer.SerializerContext;
import com.codingapi.tx.logger.TxLogger;
import com.codingapi.tx.commons.exception.TransactionException;
import com.codingapi.tx.manager.core.group.GroupRelationship;
import com.codingapi.tx.manager.core.group.TransUnit;
import com.codingapi.tx.manager.core.group.TransactionUnit;
import com.codingapi.tx.manager.core.message.MessageCreator;
import com.codingapi.tx.manager.core.message.RpcExceptionHandler;
import com.codingapi.tx.manager.support.service.TxExceptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Description: 默认事务管理器
 * Date: 19-1-9 下午5:57
 *
 * @author ujued
 */
@Slf4j
@Component
public class SimpleTransactionManager implements TransactionManager {

    private final GroupRelationship groupRelationship;

    private final RpcExceptionHandler rpcExceptionHandler;

    private final RpcClient rpcClient;

    private final TxLogger txLogger;

    private final TxExceptionService exceptionService;

    private final DTXTransactionContext transactionContext;

    @Autowired
    public SimpleTransactionManager(GroupRelationship groupRelationship,
                                    RpcExceptionHandler rpcExceptionHandler,
                                    RpcClient rpcClient, TxLogger txLogger,
                                    TxExceptionService exceptionService,
                                    DTXTransactionContext transactionContext) {
        this.rpcExceptionHandler = rpcExceptionHandler;
        this.groupRelationship = groupRelationship;
        this.exceptionService = exceptionService;
        this.rpcClient = rpcClient;
        this.txLogger = txLogger;
        this.transactionContext = transactionContext;
    }

    @Override
    public void begin(DTXTransaction dtxTransaction) {
        groupRelationship.createGroup(dtxTransaction.groupId());
    }

    @Override
    public void join(DTXTransaction dtxTransaction, TransactionUnit transactionUnit) throws TransactionException {
        TransUnit transUnit = new TransUnit();
        transUnit.setRemoteKey(transactionUnit.messageContextId());
        transUnit.setUnitType(transactionUnit.unitType());
        transUnit.setUnitId(transactionUnit.unitId());
        log.info("unit:{} joined group:{}", transactionUnit.unitId(), dtxTransaction.groupId());
        try {
            groupRelationship.joinGroup(dtxTransaction.groupId(), transUnit);
        } catch (JoinGroupException e) {
            throw new TransactionException(e);
        }
    }

    @Override
    public void commit(DTXTransaction transaction) {
        notifyTransaction(transaction.groupId(), 1);
    }

    @Override
    public void rollback(DTXTransaction transaction) {
        notifyTransaction(transaction.groupId(), 0);
    }

    @Override
    public void close(DTXTransaction groupTransaction) {
        transactionContext.destroyTransaction(groupTransaction.groupId());
        groupRelationship.removeGroup(groupTransaction.groupId());
    }

    @Override
    public int transactionState(DTXTransaction groupTransaction) {
        int state = exceptionService.transactionState(groupTransaction.groupId());
        if (state != -1) {
            return state;
        }
        return groupRelationship.transactionState(groupTransaction.groupId());
    }

    private void notifyTransaction(String groupId, int transactionState) {
        groupRelationship.setTransactionState(groupId, transactionState);
        List<TransUnit> transUnits = groupRelationship.unitsOfGroup(groupId);
        for (TransUnit transUnit : transUnits) {
            NotifyUnitParams notifyUnitParams = new NotifyUnitParams();
            notifyUnitParams.setGroupId(groupId);
            notifyUnitParams.setUnitId(transUnit.getUnitId());
            notifyUnitParams.setUnitType(transUnit.getUnitType());
            notifyUnitParams.setState(transactionState);
            txLogger.trace(groupId, notifyUnitParams.getUnitId(), Transactions.TAG_TRANSACTION, "notify unit");
            try {
                MessageDto respMsg =
                        rpcClient.request(transUnit.getRemoteKey(), MessageCreator.notifyUnit(notifyUnitParams));
                log.debug("notify unit: {}", transUnit.getRemoteKey());
                if (!MessageUtils.statusOk(respMsg)) {
                    // 提交/回滚失败的消息处理
                    List<Object> params = Arrays.asList(notifyUnitParams, transUnit.getRemoteKey());
                    rpcExceptionHandler.handleNotifyUnitBusinessException(params, throwable(respMsg.getBytes()));
                }
            } catch (RpcException | SerializerException e) {
                // 提交/回滚通讯失败
                List<Object> params = Arrays.asList(notifyUnitParams, transUnit.getRemoteKey());
                rpcExceptionHandler.handleNotifyUnitMessageException(params, e);
            } finally {
                txLogger.trace(groupId, notifyUnitParams.getUnitId(), Transactions.TAG_TRANSACTION, "notify unit over");
            }
        }
    }

    private Throwable throwable(byte[] data) throws SerializerException {
        return SerializerContext.getInstance().deSerialize(data, Throwable.class);
    }
}