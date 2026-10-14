package com.supportdesk;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** A bounded, direct loopback-only HTTP adapter. Raw model responses and thinking are never logged. */
@Component
public class OllamaClient {
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record ToolCall(String name, JsonNode arguments) {}
    public record ModelMessage(String content, List<ToolCall> calls) {}
    private final URI endpoint;
    private final String model;
    private final boolean thoughtInContent;
    private final HttpClient client;

    public OllamaClient(Environment environment) {
        model=environment.getProperty("supportdesk.ollama-model",environment.getProperty("SUPPORTDESK_OLLAMA_MODEL","")).strip();
        thoughtInContent=Boolean.parseBoolean(environment.getProperty("supportdesk.thought-in-content",environment.getProperty("SUPPORTDESK_THOUGHT_IN_CONTENT","false")));
        endpoint=localEndpoint(environment.getProperty("supportdesk.ollama-url",environment.getProperty("SUPPORTDESK_OLLAMA_URL","http://127.0.0.1:11434")));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException error) {}
            }).build();
    }
    public boolean configured() { return endpoint!=null && model.matches("[A-Za-z0-9][A-Za-z0-9_./:-]{0,119}"); }

    public ModelMessage chat(List<Map<String,Object>> messages,List<Map<String,Object>> tools,Instant deadline) {
        if(!configured()) throw new ApiException(503,"model_unavailable","未配置可用的本地模型，请使用资料检索演示");
        long remaining=Duration.between(Instant.now(),deadline).toNanos();
        if(remaining<=0) throw new ApiException(503,"model_timeout","本地模型运行已超过时限，请稍后重试");
        String payload=JSON.writeValueAsString(Map.of("model",model,"messages",messages,"tools",tools,"stream",false,"think",false,
            "options",Map.of("temperature",0,"num_predict",512,"num_ctx",8192)));
        if(payload.length()>24000) throw wire("模型上下文超过限制，请减少工单或资料内容");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(request,info -> new CappedBody());
        try {
            HttpResponse<byte[]> response=future.get(remaining,TimeUnit.NANOSECONDS);
            if(response.statusCode()!=200) throw wire("本地模型返回异常，请稍后重试");
            JsonNode result=JSON.readTree(response.body());
            if(!result.path("done").isBoolean()||!result.path("done").asBoolean()||!result.path("message").isObject()) throw wire("本地模型返回未完成或格式不正确的结果");
            return normalize(result.path("message"));
        } catch(TimeoutException e) {
            future.cancel(true); throw new ApiException(503,"model_timeout","本地模型未在时限内完成，请稍后重试");
        } catch(InterruptedException e) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ApiException(503,"model_unavailable","本地模型运行已中断");
        } catch(ExecutionException e) {
            if(e.getCause() instanceof OversizedBody) throw wire("本地模型响应超过大小限制");
            throw new ApiException(503,"model_unavailable","无法连接本地模型，请检查本地运行服务");
        } catch(ApiException e) { throw e; }
        catch(RuntimeException e) { throw wire("本地模型返回格式不正确的结果"); }
    }

    private ModelMessage normalize(JsonNode message) {
        if(!message.path("role").asText().equals("assistant")) throw wire("本地模型角色格式不正确");
        JsonNode contentNode=message.path("content");
        if(!contentNode.isMissingNode()&&!contentNode.isNull()&&!contentNode.isString()) throw wire("本地模型内容格式不正确");
        String content=contentNode.asText("").strip();
        boolean hasThought=content.contains("<think>")||content.contains("</think>");
        if(hasThought) {
            int closing=content.lastIndexOf("</think>");
            if(!thoughtInContent||closing<0) throw wire("本地模型的思考内容未形成可用结果");
            content=content.substring(closing+8).strip();
            if(content.contains("<think>")||content.contains("</think>")) throw wire("本地模型思考内容格式不正确");
        }
        List<ToolCall> calls=new ArrayList<>(); JsonNode nativeCalls=message.path("tool_calls");
        if(!nativeCalls.isMissingNode()&&!nativeCalls.isNull()) {
            if(!nativeCalls.isArray()||nativeCalls.size()>4) throw wire("本地模型工具调用数量或格式不正确");
            for(JsonNode call:nativeCalls) {
                JsonNode function=call.path("function");
                if(!function.isObject()||!function.path("name").isString()||!function.path("arguments").isObject()) throw wire("本地模型工具参数格式不正确");
                calls.add(new ToolCall(function.path("name").asText(),function.path("arguments")));
            }
        }
        if(content.contains("<tool_call>")||content.contains("</tool_call>")) {
            if(!thoughtInContent||!calls.isEmpty()) throw wire("本地模型混用了不支持的工具调用格式");
            String remaining=content;
            while(!remaining.isBlank()) {
                if(!remaining.startsWith("<tool_call>")||calls.size()>=4) throw wire("本地模型工具文本格式不正确");
                int end=remaining.indexOf("</tool_call>"); if(end<11) throw wire("本地模型工具文本不完整");
                JsonNode call=JSON.readTree(remaining.substring(11,end));
                if(!exactFields(call,"name","arguments")||!call.path("name").isString()||!call.path("arguments").isObject()) throw wire("本地模型工具文本参数不正确");
                calls.add(new ToolCall(call.path("name").asText(),call.path("arguments")));
                remaining=remaining.substring(end+12).strip();
            }
            content="";
        }
        return new ModelMessage(content,List.copyOf(calls));
    }

    static boolean exactFields(JsonNode node,String... fields) {
        return node.isObject()&&new HashSet<>(node.propertyNames()).equals(Set.of(fields));
    }
    static ApiException wire(String message) { return new ApiException(502,"model_invalid_result",message); }
    private static URI localEndpoint(String value) {
        try {
            URI base=URI.create(value); String host=base.getHost();
            if(host==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||
                !(base.getScheme().equals("http")||base.getScheme().equals("https"))||
                (base.getPath()!=null&&!base.getPath().isEmpty()&&!base.getPath().equals("/"))||base.getPort()==0||base.getPort()>65535) return null;
            if(host.equalsIgnoreCase("localhost")) host="127.0.0.1";
            if(!Set.of("127.0.0.1","::1","[::1]").contains(host)) return null;
            return new URI(base.getScheme(),null,host,base.getPort(),"/api/chat",null,null);
        } catch(RuntimeException|URISyntaxException e) { return null; }
    }
    private static final class OversizedBody extends RuntimeException {}
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> parts) {
            for(ByteBuffer part:parts) {
                if(buffer.size()+part.remaining()>65536) { subscription.cancel(); result.completeExceptionally(new OversizedBody()); return; }
                byte[] bytes=new byte[part.remaining()]; part.get(bytes); buffer.writeBytes(bytes);
            }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
