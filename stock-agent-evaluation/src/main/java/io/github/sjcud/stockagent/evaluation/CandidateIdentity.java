package io.github.sjcud.stockagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;

/** 给数据集、问题和候选回答生成内容身份，防止人工/机器对照拼错运行结果。 */
public final class CandidateIdentity {
    private CandidateIdentity() { }
    /** 使用JSON数组明确字段边界，回答中的换行或分隔符不会造成字符串拼接歧义。 */
    public static String fingerprint(String dataset, String id, String question, String answer) {
        try {
            byte[] bytes = new ObjectMapper().writeValueAsBytes(Arrays.asList(dataset, id, question, answer));
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
            return hash.toString();
        } catch (Exception ex) { throw new IllegalStateException("候选身份计算失败"); }
    }
}
