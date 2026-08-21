package com.tianji.aigc.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 基于路由工作流模式实现的智能体
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "ROUTE")
public class AgentServiceImpl implements ChatService {

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 实现思路：先把问题发给路由智能体，然后根据路由结果，调用对应的智能体进行处理
        var routeAgent = this.findAgentByType(AgentTypeEnum.ROUTE);
        var routeResult = routeAgent.process(question, sessionId);

        // 将结果尝试转化为AgentTypeEnum，如果能够转化成功，说明需要路由到其他的智能体执行，否则则返回原始结果
        var agentType = AgentTypeEnum.agentNameOf(routeResult);
        var agent = this.findAgentByType(agentType);
        if (null == agent) {
            // 未找到对应的智能体，返回原始结果
            return Flux.just(ChatEventVO.builder()
                    .eventType(ChatEventTypeEnum.DATA.getValue())
                    .eventData(routeResult)
                    .build(), AbstractAgent.STOP_EVENT);
        }
        // 找到对应的智能体，获取智能体的处理结果
        return agent.processStream(question, sessionId);
    }

    /**
     * 根据代理类型查找对应的Agent实例
     *
     * @param agentTypeEnum 要查找的代理类型
     * @return 与给定类型匹配的Agent实例，如果未找到或类型为null则返回null
     */
    private Agent findAgentByType(AgentTypeEnum agentTypeEnum) {
        if (null == agentTypeEnum) {
            return null;
        }

        // 查找Spring容器中所有的Agent实例
        var agents = SpringUtil.getBeansOfType(Agent.class);

        // 遍历所有的Agent实例，找到与给定类型匹配的实例
        for (Agent agent : agents.values()) {
            if (agent.getAgentType() == agentTypeEnum) {
                // 返回匹配的实例
                return agent;
            }
        }

        // 如果未找到匹配的实例，则返回null
        return null;
    }

    @Override
    public void stop(String sessionId) {
        var routeAgent = this.findAgentByType(AgentTypeEnum.ROUTE);
        routeAgent.stop(sessionId);
    }

    @Override
    public String chatText(String question) {
        return "";
    }
}
