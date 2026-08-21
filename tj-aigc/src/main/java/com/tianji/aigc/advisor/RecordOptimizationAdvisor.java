package com.tianji.aigc.advisor;

import cn.hutool.core.map.MapUtil;
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
public class RecordOptimizationAdvisor implements BaseAdvisor {

    private final MyChatMemoryRepository myChatMemoryRepository;

    public RecordOptimizationAdvisor(MyChatMemoryRepository myChatMemoryRepository) {
        this.myChatMemoryRepository = myChatMemoryRepository;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
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
        if (null != agentType) {
            // 需要优化记录
            var conversationId = MapUtil.getStr(chatClientResponse.context(), ChatMemory.CONVERSATION_ID);
            this.myChatMemoryRepository.optimization(conversationId);
        }

        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }
}
