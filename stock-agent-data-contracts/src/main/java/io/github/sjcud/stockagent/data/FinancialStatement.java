package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;

/** 财报核心字段；披露日与报告期分开，point-in-time 查询只能使用披露日不晚于研究日的记录。 */
public final class FinancialStatement {
    private final String reportPeriod, publishedAt, currency;
    private final BigDecimal revenue, netProfit;
    /** 创建经边界校验的财报记录。 */
    public FinancialStatement(String reportPeriod, String publishedAt, String currency, String revenue, String netProfit) {
        if (reportPeriod == null || publishedAt == null || currency == null) throw new IllegalArgumentException("财报身份不能为空");
        java.time.LocalDate.parse(reportPeriod); java.time.LocalDate.parse(publishedAt.substring(0,10));
        this.reportPeriod=reportPeriod; this.publishedAt=publishedAt; this.currency=currency;
        this.revenue=amount(revenue); this.netProfit=amount(netProfit);
    }
    private BigDecimal amount(String value) {
        if (value == null || !value.matches("-?[0-9]{1,18}(\\.[0-9]{1,4})?")) throw new IllegalArgumentException("财报金额格式无效");
        return new BigDecimal(value);
    }
    public String getReportPeriod(){return reportPeriod;} public String getPublishedAt(){return publishedAt;} public String getCurrency(){return currency;}
    public BigDecimal getRevenue(){return revenue;} public BigDecimal getNetProfit(){return netProfit;}
}
