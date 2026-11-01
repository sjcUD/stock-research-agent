package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;

/** 从未复权与复权日线比值推导的公司行为因子，不猜测分红或送转的具体类型。 */
public final class CorporateAction {
    private final String effectiveDate;
    private final BigDecimal priceFactor;
    private final String detail;
    /** 创建有界公司行为记录。 */
    public CorporateAction(String effectiveDate, BigDecimal priceFactor, String detail) {
        if (effectiveDate == null || priceFactor == null || priceFactor.signum() <= 0) throw new IllegalArgumentException("公司行为参数无效");
        this.effectiveDate=effectiveDate; this.priceFactor=priceFactor; this.detail=detail == null ? "" : detail;
    }
    /** 返回因子生效日。 */
    public String getEffectiveDate(){ return effectiveDate; }
    /** 返回复权价格因子。 */
    public BigDecimal getPriceFactor(){ return priceFactor; }
    /** 返回仅基于价格比值的说明。 */
    public String getDetail(){ return detail; }
}
