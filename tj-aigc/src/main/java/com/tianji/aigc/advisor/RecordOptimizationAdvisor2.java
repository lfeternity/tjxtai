package com.tianji.aigc.advisor;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.memory.MyChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 记录优化
 */
public class RecordOptimizationAdvisor2 implements BaseAdvisor {

    public static final String OPTIMIZATION = "optimization";
    private final MyChatMemoryRepository myChatMemoryRepository;

    public RecordOptimizationAdvisor2(MyChatMemoryRepository myChatMemoryRepository) {
        this.myChatMemoryRepository = myChatMemoryRepository;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        var userMessage = chatClientRequest.prompt().getUserMessage();
        // 标识是否需要优化记录，在after中进行判断
        var agentType = AgentTypeEnum.agentNameOf(userMessage.getText());
        chatClientRequest.context().put(OPTIMIZATION, agentType != null);
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // 获取大模型的响应内容
        var chatResponse = chatClientResponse.chatResponse();
        // 获取大模型的响应内容，判断内容是否是智能体的名称，如果是，优化记录，否则无需优化
        assert chatResponse != null;
        var text = chatResponse.getResult().getOutput().getText();
        var agentType = AgentTypeEnum.agentNameOf(text);
        var context = chatClientResponse.context();
        var conversationId = MapUtil.getStr(context, ChatMemory.CONVERSATION_ID);
        if (null != agentType) {
            // 大模型输出的内容为智能体的名称，需要优化记录
            this.myChatMemoryRepository.optimization(conversationId);
        }

        if (MapUtil.getBool(context, OPTIMIZATION, false)) {
            // 输入给大模型的内容为智能体的名称，需要优化记录，并且是在最后的数据时进行处理
            var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
            if (StrUtil.equals(finishReason, Constant.STOP)) {
                this.myChatMemoryRepository.optimization(conversationId);
            }
        }

        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }
}
