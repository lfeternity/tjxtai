package com.tianji.aigc.agent;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
public abstract class AbstractAgent implements Agent {

    public static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    @Resource
    private ChatClient chatClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ChatMemory chatMemory;
    @Resource
    private ChatSessionService chatSessionService;

    private static final String GENERATE_STATUS_KEY = "GENERATE_STATUS";

    @Override
    public Flux<ChatEventVO> processStream(String question, String sessionId) {
        // 生成请求id
        var requestId = this.generateRequestId();
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);
        // 将会话id转化为对话id
        var conversationId = ChatService.getConversationId(sessionId);
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();
        // 获取到当前登录的用户id
        var userId = UserContext.getUser();
        //更新会话时间
        this.chatSessionService.update(sessionId, question, userId);

        return this.getChatClientRequest(question, sessionId, requestId)
                .stream()
                .chatResponse()
                .doFirst(() -> hashOps.put(sessionId, "true")) // 生成开始时，设置标识
                .doOnError(throwable -> hashOps.delete(sessionId)) // 异常结束时，删除标识
                .doOnComplete(() -> hashOps.delete(sessionId)) // 正常结束时，删除标识
                .doOnCancel(() -> {
                    // 当输出被取消时，保存输出的内容到历史记录中
                    this.saveStopHistoryRecord(conversationId, outputBuilder.toString());
                }) // 打断输出的事件
                .takeWhile(response -> hashOps.get(sessionId) != null) // 后续生成的条件，true：继续生成，false：停止生成
                .map(chatResponse -> {
                    // 大模型生成的内容
                    var text = chatResponse.getResult().getOutput().getText();
                    // 追加到输出内容中
                    outputBuilder.append(text);

                    // 获取到消息的结束原因
                    var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                    if (StrUtil.equals(finishReason, Constant.STOP)) {
                        // 获取到消息id
                        var messageId = chatResponse.getMetadata().getId();
                        // 将消息id与请求id进行关联
                        ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                    }

                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    var result = ToolResultHolder.get(requestId);
                    if (ObjectUtil.isNotEmpty(result)) {
                        ToolResultHolder.remove(requestId);
                        // 工具被调用了，需要向前端传递参数
                        return Flux.just(ChatEventVO.builder()
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .eventData(result)
                                .build(), STOP_EVENT);
                    }
                    return Flux.just(STOP_EVENT); // 结束标识
                }));
    }

    @Override
    public String process(String question, String sessionId) {
        // 生成请求id
        var requestId = this.generateRequestId();
        // 获取到当前登录的用户id
        var userId = UserContext.getUser();
        //更新会话时间
        this.chatSessionService.update(sessionId, question, userId);

        return this.getChatClientRequest(question, sessionId, requestId)
                .call()
                .content();
    }

    private ChatClient.ChatClientRequestSpec getChatClientRequest(String question, String sessionId, String requestId) {
        return this.chatClient.prompt()
                .system(promptSystemSpec -> promptSystemSpec.text(this.systemMessage()).params(this.systemMessageParams()))
                .advisors(advisorSpec -> advisorSpec.advisors(this.advisors()).params(this.advisorParams(sessionId, requestId)))
                .tools(this.tools())
                .toolContext(this.toolContext(sessionId, requestId))
                .user(question);
    }

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        this.chatMemory.add(conversationId, new AssistantMessage(content));
    }

    private String generateRequestId() {
        return IdUtil.fastSimpleUUID();
    }

    @Override
    public void stop(String sessionId) {
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);
        hashOps.delete(sessionId);
    }

    @Override
    public Map<String, Object> advisorParams(String sessionId, String requestId) {
        // 将会话id转化为对话id
        var conversationId = ChatService.getConversationId(sessionId);
        return Map.of(ChatMemory.CONVERSATION_ID, conversationId);
    }
}
