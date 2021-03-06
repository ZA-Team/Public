package com.trade.hedge.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.trade.BaseService;
import com.trade.hedge.model.Track;
import com.trade.hedge.service.HedgeService;
import com.trade.huobi.enums.ContractDirectionEnum;
import com.trade.huobi.enums.ContractTypeEnum;
import com.trade.huobi.enums.SymbolEnum;
import com.trade.huobi.model.Result;
import com.trade.huobi.model.contract.Order;
import com.trade.huobi.model.contract.Position;
import com.trade.huobi.model.spot.Kline;
import com.trade.huobi.service.spot.SpotMarketService;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * 对冲抽象服务
 * <p>〈功能详细描述〉</p>
 *
 * @author 陈晨
 * @version 1.0
 * @date 2020/9/24
 */
public abstract class AbstractHedgeService extends BaseService implements HedgeService {

    @Autowired
    protected SpotMarketService spotMarketService;

    @Override
    public Result positionCheck(Track track) {
        // 持仓检查
        List<Position> positionList = this.getPositionList(track);
        Position buy = this.getPosition(positionList, ContractDirectionEnum.BUY);
        Position sell = this.getPosition(positionList, ContractDirectionEnum.SELL);
        logger.info("[{}] track={}, buy={}, sell={}, 持仓检查", LOG_MARK, track, buy, sell);

        // 开多下单
        if (!track.getHedgeConfig().isStopTrade() && buy == null) {
            Result result = this.open(track, ContractDirectionEnum.BUY, track.getHedgeConfig().getBasisVolume());
            logger.info("[{}] track={}, result={}, 开多下单", LOG_MARK, track, result);
        }
        // 开空下单
        if (!track.getHedgeConfig().isStopTrade() && sell == null) {
            Result result = this.open(track, ContractDirectionEnum.SELL, track.getHedgeConfig().getBasisVolume());
            logger.info("[{}] track={}, result={}, 开空下单", LOG_MARK, track, result);
        }
        if (!track.getHedgeConfig().isStopTrade() && (buy == null || sell == null)) {
            return Result.buildFail("交易开启 & (开多 || 开空)无持仓, 持仓检查不通过");
        }
        // 0: 多, 1: 空
        return Result.buildSuccess(buy, sell);
    }

    /**
     * @description 获取指定持仓信息
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 14:43
     * @param positionList, direction
     **/
    private Position getPosition(List<Position> positionList, ContractDirectionEnum direction) {
        for (Position position : positionList) {
            if (position == null) {
                continue;
            }
            if (direction.getValue().equals(position.getDirection())) {
                return position;
            }
        }
        return null;
    }

    @Override
    public void closeCheck(Track track, Position buy, Position sell) {
        if (track == null) {
            return;
        }
        /**
         * 单项止损开仓, 存在极高爆仓风险
         * 因为basis张止损而承担如此风险, 非明智之举
         */
        // 1.1, 开多止盈平仓
        Result result = this.profitClose(track, buy
                , this.calculateIncomeMultiple(track, buy, sell)
                , this.calculateCloseLossVolume(sell));
        logger.debug("[{}] track={}, result={}, Buy - 开多止盈平仓", LOG_MARK, track, result);
        // TODO 1.2开多止损开仓
        // 2.1, 开空止盈平仓
        result = this.profitClose(track, sell
                , this.calculateIncomeMultiple(track, sell, buy)
                , this.calculateCloseLossVolume(buy));
        logger.debug("[{}] track={}, result={}, Sell - 开空止盈平仓", LOG_MARK, track, result);
        // TODO 2.2开空止损开仓
    }

    /**
     * @description 计算止盈收益倍数
     * <p>
     *     1，逆向持仓 / 基础张数
     *     2，最小1倍
     * </p>
     *
     * @author 陈晨
     * @date 2020/9/26 15:00
     * @param track, position
     **/
    private BigDecimal calculateIncomeMultiple(Track track, Position position, Position reversePosition) {
        if (position == null) {
            return BigDecimal.ONE;
        }
        BigDecimal volume;
        if (reversePosition == null) {
            volume = position.getVolume();
        } else {
            volume = reversePosition.getVolume();
        }
        BigDecimal incomeMultiple = volume
                .divide(BigDecimal.valueOf(track.getHedgeConfig().getBasisVolume()), new MathContext(2))
                .multiply(track.getHedgeConfig().getProfitBasisMultiple());
        if (incomeMultiple.compareTo(BigDecimal.ONE) < 0) {
            incomeMultiple = BigDecimal.ONE;
        }
        return incomeMultiple;
    }

    /**
     * @description 计算止损追仓张数
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 15:03
     * @param position
     **/
    private long calculateCloseLossVolume(Position position) {
        if (position == null) {
            return 0;
        }
        return position.getVolume().longValue();
    }

    /**
     * @description 止盈平仓
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 15:10
     * @param track             追踪信息
     * @param position          持仓信息
     * @param incomeMultiple    止盈收益倍数
     * @param lossVolume        止损追仓张数
     **/
    private Result profitClose(Track track, Position position, BigDecimal incomeMultiple, long lossVolume) {
        if (track == null || position == null) {
            return Result.buildFail("args empty");
        }
        // 判断是否可以平仓
        if (!this.isClose(track, position, incomeMultiple, BigDecimal.ZERO)) {
            return Result.buildFail("not allowed close");
        }
        // 平仓下单
        Result result = this.closeOrder(track, position, lossVolume);
        if (result.success()) {
            return result;
        }
        // 平仓下单失败, 则全部撤单, 重新下单
        result = this.cancel(track);
        logger.info("[{}] track={}, position={}, result={}, 平仓下单失败, 则全部撤单, 重新下单"
                , LOG_MARK, track, position, result);
        return this.closeOrder(track, position, lossVolume);
    }

    /**
     * @description 判断是否可以平仓
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 15:45
     **/
    private boolean isClose(Track track, Position position, BigDecimal incomeMultiple, BigDecimal lastIncomePrice) {
        // 获取现价信息
        Kline kline = this.getKlineCurr(track.getSymbol(), ContractTypeEnum.THIS_WEEK);
        if (kline == null) {
            return false;
        }
        // 获取当前收益价格
        BigDecimal incomePrice = this.getIncomePrice(position, kline.getClose());
        // 若未达到计划收益, 则不平仓
        if (incomePrice.compareTo(track.getHedgeConfig().getIncomePricePlan().multiply(incomeMultiple)) < 0) {
            return false;
        }
        logger.info("[{}] direction={}, price={}, curr={}, income={}, 达到计划收益条件, 平仓准备"
                , LOG_MARK, position.getDirection(), position.getCostHold(), kline.getClose(), incomePrice);

        // 若已收益, 则持续追踪收益价格, 直至收益出现回落, 则平仓
        if (lastIncomePrice.compareTo(incomePrice) > 0) {
            return true;
        }
        // Nms检查一次
        this.sleep(track.getHedgeConfig().getProfitTrackIntervalTime());
        return this.isClose(track, position, incomeMultiple, incomePrice);
    }

    /**
     * @description 获取收益价格
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 15:35
     **/
    private BigDecimal getIncomePrice(Position position, BigDecimal currPrice) {
        if (ContractDirectionEnum.BUY.getValue().equals(position.getDirection())) {
            // 开多收益: 当前价 > 持仓价 + 收益价
            return currPrice.subtract(position.getCostHold());
        } else {
            // 开空收益: 当前价 < 持仓价 - 收益价
            return position.getCostHold().subtract(currPrice);
        }
    }

    /**
     * @description 平仓下单
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 16:46
     **/
    private Result closeOrder(Track track, Position position, long lossVolume) {
        // 1, 止盈平仓（所有张）
        Result result = this.close(track, position);
        logger.info("[{}] track={}, direction={}, price={}, result={}, 止盈平仓（所有, {}张）"
                , LOG_MARK, track, position.getDirection(), position.getCostHold()
                , result, position.getVolume());
        if (!result.success()) {
            return result;
        }
        // 订单完成检查
        if (!this.orderCompleteCheck(track, result, 0)) {
            logger.info("[{}] track={}, direction={}, 止盈平仓检查, 超时", LOG_MARK, track, position.getDirection());
            return Result.buildFail("止盈平仓检查, 超时");
        }

        // 2, 未停止交易, 则止盈同向新开仓
        if (!this.isStopTrade(track, position)) {
            // 2, 同向开仓（basis张）
            result = this.open(track, ContractDirectionEnum.get(position.getDirection()), track.getHedgeConfig().getBasisVolume());
            logger.info("[{}] track={}, direction={}, result={}, 同向开仓（{}}张）"
                    , LOG_MARK, track, position.getDirection(), result, track.getHedgeConfig().getBasisVolume());
            if (!result.success()) {
                return result;
            }
            // 订单完成检查
            if (!this.orderCompleteCheck(track, result, 0)) {
                logger.info("[{}] track={}, direction={}, 同向开仓检查, 超时", LOG_MARK, track, position.getDirection());
                return Result.buildFail("同向开仓检查, 超时");
            }
        }

        // 3, 逆向止损加仓（lossVolume张）
        if (lossVolume > 0) {
            result = this.open(track, ContractDirectionEnum.get(position.getDirection()).getNegate(), lossVolume);
            logger.info("[{}] track={}, direction={}, result={}, 逆向止损加仓（{}张）"
                    , LOG_MARK, track, ContractDirectionEnum.get(position.getDirection()).getNegate()
                    , result, lossVolume);
        }
        return Result.buildSuccess();
    }

    /**
     * @description 订单完成检查
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 20:34
     **/
    private boolean orderCompleteCheck(Track track, Result result, int count) {
        if (count > track.getHedgeConfig().getTimeout()) {
            return false;
        }
        String orderId = JSONObject.parseObject(result.getData().toString()).getLong("order_id") + "";
        Order order = this.getOrderInfo(track, orderId);
        if (order != null && order.getStatus() == 6) {
            return true;
        }
        this.sleep(1000);
        return this.orderCompleteCheck(track, result, ++count);
    }

    /**
     * @description 止损开仓
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/28 17:58
     * @param track, position, incomeMultiple, lossVolume
     **/
    private Result lossOpen(Track track, Position position, BigDecimal incomeMultiple, long lossVolume) {

        return Result.buildSuccess();
    }

    /**
     * @description 获取持仓信息
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 15:16
     * @param track
     **/
    protected abstract List<Position> getPositionList(Track track);

    /**
     * @description 获取当前K线信息
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/28 17:46
     * @param symbol, contractType
     **/
    protected abstract Kline getKlineCurr(SymbolEnum symbol, ContractTypeEnum contractType);

    /**
     * @description 开仓
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 15:55
     * @param track, direction, volume
     **/
    protected abstract Result open(Track track, ContractDirectionEnum direction, long volume);

    /**
     * @description 平仓
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/24 15:55
     * @param track, direction
     **/
    protected abstract Result close(Track track, Position position);

    /**
     * @description 撤单
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 15:07
     * @param track
     **/
    protected abstract Result cancel(Track track);

    /**
     * @description 是否停止交易
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 13:30
     * @param track, position
     **/
    protected abstract boolean isStopTrade(Track track, Position position);

    /**
     * @description 获取指定订单信息
     * <p>〈功能详细描述〉</p>
     *
     * @author 陈晨
     * @date 2020/9/26 13:29
     * @param track, orderId
     **/
    protected abstract Order getOrderInfo(Track track, String orderId);

}


