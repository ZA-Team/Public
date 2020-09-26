package com.trade.hedge.service.impl;

import com.trade.analyse.model.trade.Track;
import com.trade.hedge.context.HedgeContext;
import com.trade.huobi.enums.ContractDirectionEnum;
import com.trade.huobi.enums.ContractOffsetEnum;
import com.trade.huobi.enums.ContractOrderPriceTypeEnum;
import com.trade.huobi.enums.ContractTypeEnum;
import com.trade.huobi.model.Result;
import com.trade.huobi.model.contract.Order;
import com.trade.huobi.model.contract.Position;
import com.trade.huobi.service.contract.ContractAccountService;
import com.trade.huobi.service.contract.ContractTradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对冲服务：交割合约实现
 * <p>〈功能详细描述〉</p>
 *
 * @author 陈晨
 * @version 1.0
 * @date 2020/9/24
 */
@Service
public class ContractHedgeServiceImpl extends AbstractHedgeService {

    @Autowired
    private ContractAccountService contractAccountService;
    @Autowired
    private ContractTradeService contractTradeService;

    @Override
    protected List<Position> getPositionList(Track track) {
        return contractAccountService.getPositionList(track.getAccess(), track.getSecret(), track.getSymbol());
    }

    @Override
    protected Result open(Track track, ContractDirectionEnum direction, long volume) {
        return contractTradeService.order(track.getAccess(), track.getSecret(), track.getSymbol(), ContractTypeEnum.THIS_WEEK
                , null, volume, direction, ContractOffsetEnum.OPEN
                , track.getLeverRate(), ContractOrderPriceTypeEnum.OPTIMAL_5);
    }

    @Override
    protected Result close(Track track, Position position) {
        return contractTradeService.order(track.getAccess(), track.getSecret(), track.getSymbol(), ContractTypeEnum.THIS_WEEK
                , null, position.getVolume().longValue()
                , ContractDirectionEnum.get(position.getDirection()).getNegate(), ContractOffsetEnum.CLOSE
                , track.getLeverRate(), ContractOrderPriceTypeEnum.OPTIMAL_5);
    }

    @Override
    protected Result cancel(Track track) {
        return contractTradeService.cancelAll(track.getAccess(), track.getSecret(), track.getSymbol());
    }

    @Override
    protected boolean isStopTrade(Track track, Position position) {
        // 停止交易, 无持仓 || 平仓张数 > basis, 则不再向下追仓
        if (!HedgeContext.isStopTrade()) {
            return false;
        }
        Position positionCheck = contractAccountService.getPositionInfo(track.getAccess(), track.getSecret(), track.getSymbol());
        return positionCheck == null || position.getVolume().compareTo(BigDecimal.valueOf(track.getBasisVolume())) > 0;
    }

    @Override
    protected Order getOrderInfo(Track track, String orderId) {
        return contractTradeService.getOrderInfo(track.getAccess(), track.getSecret(), track.getSymbol(), orderId);
    }

}

