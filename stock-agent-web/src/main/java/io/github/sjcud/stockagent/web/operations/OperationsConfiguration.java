package io.github.sjcud.stockagent.web.operations;

import com.mysql.cj.jdbc.MysqlDataSource;

import io.qdrant.client.QdrantClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.sql.Connection;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** 本地部署的依赖装配；当前仅检查MySQL连通，不自动改变现有内存研究仓储。 */
@Configuration
public class OperationsConfiguration {
    /** 有界连接用于就绪探测；配置显式启用时才创建，不触发建表或数据写入。 */
    @Bean
    @ConditionalOnProperty(name = "stock.agent.mysql.enabled", havingValue = "true")
    public DataSource deploymentDataSource(
            @Value("${stock.agent.mysql.url:jdbc:mysql://127.0.0.1:3307/stock_agent}") String url,
            @Value("${stock.agent.mysql.user:stock}") String user,
            @Value("${stock.agent.mysql.password:}") String password)
            throws java.sql.SQLException {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(url);
        source.setUser(user);
        source.setPassword(password);
        source.setConnectTimeout(2000);
        source.setSocketTimeout(2000);
        return source;
    }

    /** 将启用依赖纳入readiness；不调用模型API，仅检查key/model/baseURL是否完整。 */
    @Bean
    public DependencyHealthIndicator stockDependenciesHealthIndicator(
            ObjectProvider<QdrantClient> qdrantProvider,
            ObjectProvider<DataSource> mysqlProvider,
            @Value("${stock.agent.health.require-models:false}") boolean requireModels,
            @Value("${stock.agent.chat.api-key:}") String chatKey,
            @Value("${stock.agent.chat.base-url:}") String chatBase,
            @Value("${stock.agent.chat.model:}") String chatModel,
            @Value("${stock.agent.embedding.api-key:}") String embeddingKey,
            @Value("${stock.agent.embedding.base-url:}") String embeddingBase,
            @Value("${stock.agent.embedding.model:}") String embeddingModel) {
        QdrantClient qdrant = qdrantProvider.getIfAvailable();
        DataSource mysql = mysqlProvider.getIfAvailable();
        Supplier<Boolean> qdrantProbe = qdrant == null ? null : () -> qdrantAvailable(qdrant);
        Supplier<Boolean> mysqlProbe = mysql == null ? null : () -> mysqlAvailable(mysql);
        boolean configured =
                present(chatKey)
                        && present(chatBase)
                        && present(chatModel)
                        && present(embeddingKey)
                        && present(embeddingBase)
                        && present(embeddingModel);
        return new DependencyHealthIndicator(qdrantProbe, mysqlProbe, requireModels, configured);
    }

    /** gRPC探测有单次等待上限；超时/中断时取消该探测Future。 */
    private boolean qdrantAvailable(QdrantClient client) {
        Future<?> future = client.listCollectionsAsync();
        try {
            future.get(2, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    /** 实际建立连接并验证，try-with-resources保证每次健康轮询关闭连接。 */
    private boolean mysqlAvailable(DataSource source) {
        try (Connection connection = source.getConnection()) {
            return connection.isValid(2);
        } catch (java.sql.SQLException ex) {
            return false;
        }
    }

    /** 只判断是否配置，不在异常或状态中返回原值。 */
    private boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
