package io.stream.quotes.ranking;

import java.util.Set;

@FunctionalInterface
public interface TradableSymbols {
    /** Binance symbols currently status=TRADING with quoteAsset=USDT, or an EMPTY set
     *  when unknown/unavailable (the caller must NOT filter in that case — soft fallback). */
    Set<String> usdtTradingSymbols();
}
