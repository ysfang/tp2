package name.qd.tp2.myImpl;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import name.qd.tp2.constants.BuySell;
import name.qd.tp2.exchanges.ExchangeManager;
import name.qd.tp2.exchanges.vo.Fill;
import name.qd.tp2.exchanges.vo.Orderbook;
import name.qd.tp2.strategies.AbstractStrategy;
import name.qd.tp2.strategies.config.JsonStrategyConfig;
import name.qd.tp2.strategies.config.StrategyConfig;
import name.qd.tp2.utils.LineNotifyUtils;

/**
	進場: 等比價量下跌吸貨
		 EX: 
		 設定第一單contract size = 10, price range = 10
		 若第一單於3000吸貨10(contract size)張
		 第二單將於2990(3000 - price range)吸貨 10張
		 第三單將於2970(2990 - price range * 2)吸貨 20(contract size * 2)張
		 ...
	出場: 均價以上定值或定比例出貨
		 EX:
		 依照進場均價
		 若設定定值出場 出場價 = 均價 + profit price
		 若設定獲利趴數出場 出場價 = 均價 * (1 + profit rate)
	其他: 
 */
public class GridStrategy extends AbstractStrategy {
	private Logger log = LoggerFactory.getLogger(GridStrategy.class);
	private LineNotifyUtils lineNotifyUtils;
	// 與交易所溝通用
	private ExchangeManager exchangeManager = ExchangeManager.getInstance();
	
	private String strategyName = "[等比網格]";
	private String userName = "shawn";

	// 自己設定一些策略內要用的變數
	private String symbol = "ETHPFC";
	private String firstOrderId;
	private String stopProfitOrderId;
	private int position = 0;
	private double averagePrice = 0;
	private double targetPrice = 0;
	private double currentFees = 0;
	private Set<String> setOrderId = new HashSet<>();

	// 這個策略要用到的值 全部都設定在config
	private int priceRange;
	private int firstContractSize;
	private String stopProfitType;
	private double stopProfit;
	private double fee;
	private double tickSize;
	private int orderLevel;
	private String lineNotify;
	
	private boolean isStartWithRemain;
	
	private long from;
	private long to;
	
	public GridStrategy(StrategyConfig strategyConfig) {
		super(strategyConfig);

		// 把設定值從config讀出來
		priceRange = Integer.parseInt(strategyConfig.getCustomizeSettings("priceRange"));
		firstContractSize = Integer.parseInt(strategyConfig.getCustomizeSettings("firstContractSize"));
		stopProfitType = strategyConfig.getCustomizeSettings("stopProfitType");
		stopProfit = Double.parseDouble(strategyConfig.getCustomizeSettings("stopProfit"));
		fee = Double.parseDouble(strategyConfig.getCustomizeSettings("fee"));
		tickSize = Double.parseDouble(strategyConfig.getCustomizeSettings("tickSize"));
		orderLevel = Integer.parseInt(strategyConfig.getCustomizeSettings("orderLevel"));
		lineNotify = strategyConfig.getCustomizeSettings("lineNotify");
		
		lineNotifyUtils = new LineNotifyUtils(lineNotify);
		
		ZonedDateTime zonedDateTime = ZonedDateTime.now();
		from = zonedDateTime.toEpochSecond() * 1000;
		to = zonedDateTime.toEpochSecond() * 1000;
		
		// 若有設定剩餘未平倉單
		// 讓策略可以接續
		isStartWithRemain = (strategyConfig.getCustomizeSettings("remainQty") != null && strategyConfig.getCustomizeSettings("remainFirstOrderPrice") != null);
	}
	
	private void startWithRemain() {
		log.info("有未平倉單接續策略 ");
		position = Integer.parseInt(strategyConfig.getCustomizeSettings("remainQty"));
		double remainFirstOrderPrice = Double.parseDouble(strategyConfig.getCustomizeSettings("remainFirstOrderPrice"));
		log.info("remaining qty: {}, first remaining order price: {}", position, remainFirstOrderPrice);
		averagePrice = calcCurrentAvgPrice(remainFirstOrderPrice);
		
		// 下停利
		targetPrice = getTargetPrice(averagePrice);
		placeStopProfitOrder();
		// 鋪單
		int startLevel = getRemainOrderLevel(position);
		placeLevelOrders(startLevel, remainFirstOrderPrice);
	}

	@Override
	public void strategyAction() {
		if(isStartWithRemain) {
			startWithRemain();
			isStartWithRemain = false;
		}
		checkFill();
		if(position == 0) {
			setFirstOrder();
		} else {
			checkFirstOrderProfit();
		}
		
		// 給GTC多一點時間 且電腦要爆了
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private void setFirstOrder() {
		if(firstOrderId != null) {
			// cancel order
			if(!cancelOrder(firstOrderId)) {
				log.error("刪除第一筆單失敗 orderId:{}", firstOrderId);
				lineNotifyUtils.sendMessage(strategyName + "刪除第一單失敗");
			} else {
				log.debug("刪除未成交第一單 {}", firstOrderId);
				firstOrderId = null;
			}
		}
		
		// 上面刪單成功 id變null, 或是本來就沒單
		if(firstOrderId == null && stopProfitOrderId == null) {
			Orderbook orderbook = exchangeManager.getOrderbook(ExchangeManager.BTSE_EXCHANGE_NAME, symbol);
			if(orderbook == null) return;
			double price = orderbook.getBidTopPrice(1)[0] + tickSize;
			// 可能要用賣價減一個ticksize
			String orderId = sendOrder(BuySell.BUY, price, firstContractSize);
			if(orderId != null) {
				log.info("送出第一單 {},{} , {}", price, firstContractSize, orderId);
				firstOrderId = orderId;
			} else {
				log.error("送出第一單失敗");
			}
		}
	}
	
	private void checkFirstOrderProfit() {
		// 第一單存在的狀況下
		// 如果第一單達目標價
		// 將第一單算成新的一次交易的第一單
		// 重新鋪單
		if(position == firstContractSize) {
			// 第一單完全成交狀態下
			Orderbook orderbook = exchangeManager.getOrderbook(ExchangeManager.BTSE_EXCHANGE_NAME, symbol);
			if(orderbook != null) {
				double price = orderbook.getBidTopPrice(1)[0];
				if(price >= targetPrice) {
					log.info("第一單到達停利價格 直接當下一單的第一單");
					// 刪除所有鋪單
					cancelOrder(null);
					// 更新成本價格
					averagePrice = targetPrice;
					// 更新停利價格
					targetPrice = getTargetPrice(averagePrice);
					// 重新鋪單
					placeLevelOrders(1, averagePrice);
				}
			}
		}
	}

	private void checkFill() {
		ZonedDateTime zonedDateTime = ZonedDateTime.now();
		to = zonedDateTime.toEpochSecond() * 1000;
		
		List<Fill> lstFill = exchangeManager.getFillHistory(ExchangeManager.BTSE_EXCHANGE_NAME, userName, symbol, from, to);
		
		if(lstFill == null) return;
		from = to;
		
//		List<Fill> lstFill = exchangeManager.getFill(strategyName, ExchangeManager.BTSE_EXCHANGE_NAME);
		for(Fill fill : lstFill) {
			// 濾掉不是此策略的成交
			if(!setOrderId.contains(fill.getOrderId()) && !fill.getOrderId().equals(stopProfitOrderId)) continue;
			
			log.debug("收到成交 {} {} {}", fill.getOrderPrice(), fill.getQty(), fill.getOrderId());
			int qty = Integer.parseInt(fill.getQty());
			double orderPrice = Double.parseDouble(fill.getOrderPrice());
			if(position < firstContractSize) {
				averagePrice = ((position * averagePrice) + (orderPrice * qty)) / (position + qty);
				position += qty;
				if(position == firstContractSize) {
					log.info("第一單完全成交");
					lineNotifyUtils.sendMessage(strategyName + "第一單完全成交");
					// 更新目標價
					targetPrice = getTargetPrice(averagePrice);
					// 完全成交
					// 鋪單
					placeLevelOrders(1, averagePrice);
				} else {
					log.warn("第一單部分成交 {} {}", fill.getOrderPrice(), qty);
					lineNotifyUtils.sendMessage(strategyName + "第一單部分成交");
				}
			} else {
				if(fill.getOrderId().equals(stopProfitOrderId)) {
					// 停利單成交
					position -= qty;
					if(position == firstContractSize ) {
						log.info("停利單完全成交");
						lineNotifyUtils.sendMessage(strategyName + "停利單完全成交");
						stopProfitOrderId = null;
						// 計算獲利
						calcProfit(qty, orderPrice);
						// 重算成本
						averagePrice = orderPrice;
						// 重算目標價
						targetPrice = getTargetPrice(averagePrice);
						// 刪除之前鋪單
						cancelOrder(null);
						// 鋪單
						placeLevelOrders(1, orderPrice);
					} else {
						log.warn("停利單部分成交 {}, {}", fill.getOrderPrice(), fill.getQty());
						lineNotifyUtils.sendMessage(strategyName + "停利單部分成交" + fill.getQty());
						calcProfit(qty, orderPrice);
					}
				} else {
					// 一般單成交
					
					// 重算成本  改停利單
					averagePrice = ((position * averagePrice) + (orderPrice * qty)) / (position + qty);
					position += qty;
					// 清除舊的停利單
					if(stopProfitOrderId != null) {
						if(!cancelOrder(stopProfitOrderId)) {
							log.error("清除舊的停利單失敗 orderId:{}", stopProfitOrderId);
							lineNotifyUtils.sendMessage(strategyName + "清除舊的停利單失敗");
						} else {
							stopProfitOrderId = null;
							log.info("清除舊的停利單");
						}
					}
					// 下新的停利單
					targetPrice = getTargetPrice(averagePrice);
					placeStopProfitOrder();
				}
			}
		}
	}
	
	private void placeLevelOrders(int startLevel, double basePrice) {
		for(int i = 1 ; i < orderLevel ; i++) {
			basePrice = basePrice - (priceRange * Math.pow(2, i-1));
			double qty = firstContractSize * Math.pow(2, i-1);
			if(i == startLevel) {
				String orderId = sendOrder(BuySell.BUY, basePrice, qty);
				if(orderId != null) {
					log.info("鋪單 {} {} {}", i, basePrice, qty);
					setOrderId.add(orderId);
				} else {
					log.error("鋪單失敗 {} {} {}", i, basePrice, qty);
					lineNotifyUtils.sendMessage(strategyName + "鋪單失敗");
				}
				startLevel++;
			}
		}
	}
	
	private void placeStopProfitOrder() {
		if(position - firstContractSize > 0) {
			String orderId = sendOrder(BuySell.SELL, targetPrice, position - firstContractSize);
			if(orderId != null) {
				stopProfitOrderId = orderId;
				log.info("下停利單 {} {} {}", targetPrice, position - firstContractSize, orderId);
			} else {
				log.warn("下停利單失敗 {} {}", targetPrice, position - firstContractSize);
				lineNotifyUtils.sendMessage(strategyName + "下停利單失敗");
			}
		}
	}
	
	private String sendOrder(BuySell buySell, double price, double qty) {
		return exchangeManager.sendOrder(strategyName, ExchangeManager.BTSE_EXCHANGE_NAME, userName, symbol, buySell, price, qty);
	}
	
	private boolean cancelOrder(String orderId) {
		if(orderId == null) {
			for(String id : setOrderId) {
				if(id != null) {
					exchangeManager.cancelOrder(strategyName, ExchangeManager.BTSE_EXCHANGE_NAME, userName, symbol, id);
				}
			}
			setOrderId.clear();
			return true;
		} else {
			return exchangeManager.cancelOrder(strategyName, ExchangeManager.BTSE_EXCHANGE_NAME, userName, symbol, orderId);
		}
	}
	
	private int getRemainOrderLevel(int remainQty) {
		if(remainQty < firstContractSize) {
			log.error("設定錯誤: RemainQty < firstContractSize");
			return 0;
		}
		int level = 0;
		while(remainQty > 0) {
			level++;
			remainQty -= firstContractSize * level;
		}
		return level;
	}
	
	private double calcCurrentAvgPrice(double firstOrderPrice) {
		double cost = firstOrderPrice * firstContractSize;
		int qty = position - firstContractSize;
		double price = firstOrderPrice;
		for(int i = 1 ; qty > 0 ; i++) {
			price -= (priceRange * Math.pow(2, i-1));
			double size = firstContractSize * Math.pow(2, i-1);
			cost +=  price * size;
			qty -= size;
		}
		return cost / position;
	}
	
	private double getTargetPrice(double price) {
		if(stopProfitType.equals("rate")) {
			price =  price * stopProfit;
			price -= price % tickSize;
		} else if(stopProfitType.equals("fix")) {
			price =  price + stopProfit;
		} else {
			price = price * 1.008d;
			price -= price % tickSize;
			log.error("unknown stop profit type: {}", stopProfitType);
		}
		return price;
	}
	
	private void calcProfit(double qty, double price) {
		double priceDiff = price - averagePrice;
		double profit = priceDiff * qty / 100;
		lineNotifyUtils.sendMessage(strategyName + "獲利: " + profit);
		log.info("獲利: {}", profit);
	}

	public static void main(String[] s) {
		Properties prop = System.getProperties();
		prop.setProperty("log4j.configurationFile", "./config/log4j2.xml");
		
		try {
//			String configPath = "./config/testnet.json";
			String configPath = "./config/test.json";
			GridStrategy strategy = new GridStrategy(new JsonStrategyConfig(configPath));
			strategy.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
