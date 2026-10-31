package io.github.sjcud.stockagent.data;

import java.io.IOException;

/** 最小 HTTP 读接口，供应商解析可通过固定响应做离线测试。 */
public interface HttpTextClient {
    /** 读取受限大小的 UTF-8 响应；超时、取消、限流和非 200 均抛出异常。 */
    String get(String url) throws IOException;
}
