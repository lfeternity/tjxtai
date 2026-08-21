package com.tianji.aigc.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强型智能体实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "ENHANCE")
public class ChatServiceImpl implements ChatService {

    public static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();
    private final ChatClient chatClient;
    private final ChatClient openAiChatClient;
    private final SystemPromptConfig systemPromptConfig;
    private final ChatMemory chatMemory;
    private final StringRedisTemplate stringRedisTemplate;
    private final VectorStore vectorStore;
    private final ChatSessionService chatSessionService;
    // 通过一个容器，保存当前会话的会话ID 以及 是否继续生成的标识，用于后续停止会话
    // 容器实现：1、使用Map， 2、如果考虑到分布式场景的话，需要使用redis
    // private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();
    private static final String GENERATE_STATUS_KEY = "GENERATE_STATUS";

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 将会话id转化为对话id
        var conversationId = ChatService.getConversationId(sessionId);
        // 大模型输出内容的缓存器，用于在输出中断后的数据存储
        var outputBuilder = new StringBuilder();
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);

        // 生成请求id
        var requestId = IdUtil.simpleUUID();

        // 获取到当前登录的用户id
        var userId = UserContext.getUser();

        // 定义RAG增强
        var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.6d) // 相似度阈值
                        .topK(6) // 搜索的条数
                        .build())
                .build();

        // 更新会话标题或更新时间
        this.chatSessionService.update(sessionId, question, userId);

        return this.chatClient.prompt()
                .system(promptSystem -> promptSystem
                        .text(this.systemPromptConfig.getChatSystemMessage().get()) // 系统提示词
                        .params(Map.of("now", DateUtil.now())) // 系统提示词当前时间参数
                )
                .advisors(advisor -> advisor
                        .advisors(qaAdvisor) // 添加RAG增强
                        .param(ChatMemory.CONVERSATION_ID, conversationId)) // 设置对话记忆中的对话id
                .toolContext(Map.of(Constant.REQUEST_ID, requestId, Constant.USER_ID, userId)) // 向工具中传递参数
                .user(question)
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

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        this.chatMemory.add(conversationId, new AssistantMessage(content));
    }

    @Override
    public void stop(String sessionId) {
        var hashOps = this.stringRedisTemplate.boundHashOps(GENERATE_STATUS_KEY);
        hashOps.delete(sessionId);
    }

    @Override
    public String chatText(String question) {
        return this.openAiChatClient.prompt()
                .system(this.systemPromptConfig.getTextSystemMessage().get())
                .user(question)
                .call().content();
    }
}
