package com.tianji.aigc.agent;

import cn.hutool.core.map.MapUtil;
import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.utils.JsonUtils;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class AppTest {

    @Test
    public void testAppCall() throws Exception {
        String apiKey = System.getenv("ALIYUN_API_KEY");
        String appId = System.getenv("DASHSCOPE_APP_ID");
        String toolId = System.getenv("DASHSCOPE_TOOL_ID");
        String token = System.getenv("TJXT_TEST_USER_TOKEN");
        Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank()
                        && appId != null && !appId.isBlank()
                        && toolId != null && !toolId.isBlank()
                        && token != null && !token.isBlank(),
                "需要配置 AI 联调环境变量");

        // 构造业务参数
        Map<String, Object> bizParams = MapUtil.<String, Object>builder()
                .put("user_defined_tokens", MapUtil.of(toolId,
                        MapUtil.of("user_token", token)))
                .build();

        // bizParams.add("user_defined_tokens", JsonObject);
        ApplicationParam param = ApplicationParam.builder()
                // 若没有配置环境变量，可用百炼API Key将下行替换为：.apiKey("sk-xxx")。但不建议在生产环境中直接将API Key硬编码到代码中，以减少API Key泄露风险。
                .apiKey(apiKey)
                .appId(appId) // 智能体id
                .prompt("课程推荐，20岁，本科，没有编程基础，对java感兴趣")
                .incrementalOutput(true) // 开启增量输出
                .bizParams(JsonUtils.toJsonObject(bizParams))
                .build();

        Application application = new Application();
        Flowable<ApplicationResult> result = application.streamCall(param);

        // 阻塞式的打印内容
        result.blockingForEach(data -> {
            System.out.printf("%s\n",data.getOutput().getText());
        });

    }

}
