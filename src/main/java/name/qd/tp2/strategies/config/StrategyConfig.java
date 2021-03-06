package name.qd.tp2.strategies.config;

import java.util.Set;

import name.qd.tp2.exchanges.vo.ApiKeySecret;

public interface StrategyConfig {
	public Set<String> getAllExchange();
	public String getExchangeEvn(String exchange);
	public Set<String> getAllSymbols(String exchange);
	public void addSymbol(String exchange, String symbol);
	public String getCustomizeSettings(String key);
	
	public Set<String> getAllUser(String exchange);
	public ApiKeySecret getApiKeySecret(String exchange, String user);
}
