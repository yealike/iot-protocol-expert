package top.yealike.pep.modelgateway.chat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.model.contract.RunTokenPayload;
import top.yealike.pep.modelgateway.auth.GatewayAuthFilter;
import top.yealike.pep.modelgateway.quota.QuotaService;

class ChatCompletionsControllerStreamingTest {
    @Test
    void streamsSseThroughMvcAndSettlesReportedUsage() throws Exception {
        var calls = mock(ModelCallService.class);
        var providers = mock(ProviderClient.class);
        var prepared = mock(ModelCallService.PreparedCall.class);
        when(prepared.stream()).thenReturn(true);
        when(calls.prepare(any(), anyMap(), isNull(), isNull())).thenReturn(prepared);
        doAnswer(invocation -> {
                    ProviderClient.LineSink sink = invocation.getArgument(2);
                    sink.accept(
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}");
                    sink.accept("");
                    sink.accept("data: [DONE]");
                    sink.accept("");
                    return null;
                })
                .when(providers)
                .stream(any(), any(), any());
        var mvc = MockMvcBuilders.standaloneSetup(new ChatCompletionsController(
                        calls, providers, JsonMapper.builder().build()))
                .build();
        var result = mvc.perform(post("/v1/chat/completions")
                        .requestAttr(GatewayAuthFilter.RUN_TOKEN_ATTRIBUTE, mock(RunTokenPayload.class))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"model\":\"test-model\",\"stream\":true}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data: [DONE]")));
        verify(calls)
                .settle(
                        eq(prepared),
                        argThat(usage -> Integer.valueOf(17).equals(usage.get("total_tokens"))),
                        isNull(),
                        isNull());
    }

    @Test
    void preservesJsonCompletionWithTheTypedResponse() throws Exception {
        var calls = mock(ModelCallService.class);
        var providers = mock(ProviderClient.class);
        var prepared = mock(ModelCallService.PreparedCall.class);
        when(calls.prepare(any(), anyMap(), isNull(), isNull())).thenReturn(prepared);
        when(providers.complete(any(), any()))
                .thenReturn(new ProviderClient.ProviderResponse(
                        200, "application/json", "{\"choices\":[],\"usage\":{\"total_tokens\":17}}"));
        var mvc = MockMvcBuilders.standaloneSetup(new ChatCompletionsController(
                        calls, providers, JsonMapper.builder().build()))
                .build();
        var result = mvc.perform(post("/v1/chat/completions")
                        .requestAttr(GatewayAuthFilter.RUN_TOKEN_ATTRIBUTE, mock(RunTokenPayload.class))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"test-model\",\"stream\":false}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.usage.total_tokens").value(17));
    }

    @Test
    void interruptedStreamMarksReservationUncertainWithoutSettlingUsage() throws Exception {
        var calls = mock(ModelCallService.class);
        var providers = mock(ProviderClient.class);
        var prepared = mock(ModelCallService.PreparedCall.class);
        var reservation = mock(QuotaService.ReservationHandle.class);
        when(prepared.stream()).thenReturn(true);
        when(prepared.reservation()).thenReturn(reservation);
        when(calls.prepare(any(), anyMap(), isNull(), isNull())).thenReturn(prepared);
        doThrow(new ProviderClient.UpstreamStreamException(new java.io.IOException("missing done marker")))
                .when(providers)
                .stream(any(), any(), any());
        var mvc = MockMvcBuilders.standaloneSetup(new ChatCompletionsController(
                        calls, providers, JsonMapper.builder().build()))
                .build();

        var result = mvc.perform(post("/v1/chat/completions")
                        .requestAttr(GatewayAuthFilter.RUN_TOKEN_ATTRIBUTE, mock(RunTokenPayload.class))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"model\":\"test-model\",\"stream\":true}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        verify(calls).uncertain(prepared, null);
        verify(calls, never()).settle(any(), any(), any(), any());
    }
}
