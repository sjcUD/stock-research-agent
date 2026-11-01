package io.github.sjcud.stockagent.llm;

/** 单次模型回答与供应商usage；null明确表示未提供，不用估算冒充账单。 */
public final class ChatCompletion {
    /** 回答文本、供应商上报输入/输出Token。 */
    private final String text;
    private final Integer inputTokens, outputTokens;
    /** 校验非负usage，缺失时仍允许返回文本。 */
    public ChatCompletion(String text, Integer input, Integer output) {
        if (text == null || (input != null && input < 0) || (output != null && output < 0))
            throw new IllegalArgumentException("模型响应无效");
        this.text = text; inputTokens = input; outputTokens = output;
    }
    /** 返回正文。 */
    public String getText() { return text; }
    /** 返回实际输入Token，缺失为null。 */
    public Integer getInputTokens() { return inputTokens; }
    /** 返回实际输出Token，缺失为null。 */
    public Integer getOutputTokens() { return outputTokens; }
}
