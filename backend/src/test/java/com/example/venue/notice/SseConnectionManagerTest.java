package com.example.venue.notice;

import com.example.venue.notice.service.SseConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SseConnectionManagerTest {

    @Test
    void sendRemovesDisconnectedEmitterWithoutCompletingAsyncResponse() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter emitter = mock(SseEmitter.class);
        manager.register(1L, emitter);
        doThrow(new AsyncRequestNotUsableException("Response not usable after response errors."))
            .when(emitter)
            .send(any(SseEmitter.SseEventBuilder.class));

        assertThatNoException().isThrownBy(() -> manager.send(1L, "payload"));

        assertThat(emitterMap(manager)).doesNotContainKey(1L);
        verify(emitter, never()).complete();
    }

    @Test
    void sendUsesHealthyEmitterNormally() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter emitter = mock(SseEmitter.class);
        manager.register(11L, emitter);

        assertThatNoException().isThrownBy(() -> manager.send(11L, "payload"));

        assertThat(emitterMap(manager)).containsKey(11L);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void sendPropagatesNonDisconnectRuntimeFailure() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter emitter = mock(SseEmitter.class);
        manager.register(2L, emitter);
        doThrow(new IllegalArgumentException("programming bug"))
            .when(emitter)
            .send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> manager.send(2L, "payload"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("programming bug");

        assertThat(emitterMap(manager)).containsKey(2L);
        verify(emitter, never()).complete();
    }

    @Test
    void sendRemovesDisconnectedEmitterWithoutCompletingClosedResponse() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter emitter = mock(SseEmitter.class);
        manager.register(3L, emitter);
        doThrow(new IOException("client disconnected"))
            .when(emitter)
            .send(any(SseEmitter.SseEventBuilder.class));
        assertThatNoException().isThrownBy(() -> manager.send(3L, "payload"));

        assertThat(emitterMap(manager)).doesNotContainKey(3L);
        verify(emitter, never()).complete();
    }

    @Test
    void sendToAllRemovesDisconnectedEmitterAndContinues() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter staleEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        manager.register(4L, staleEmitter);
        manager.register(5L, healthyEmitter);
        doThrow(new AsyncRequestNotUsableException("Response not usable after response errors."))
            .when(staleEmitter)
            .send(any(SseEmitter.SseEventBuilder.class));
        doNothing().when(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatNoException().isThrownBy(() -> manager.sendToAll("payload"));

        assertThat(emitterMap(manager)).doesNotContainKey(4L).containsKey(5L);
        verify(staleEmitter, never()).complete();
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthyEmitter, never()).complete();
    }

    @Test
    void sendToAllRemovesEmitterWhenDisconnectIsWrappedByRuntimeException() throws Exception {
        SseConnectionManager manager = new SseConnectionManager();
        SseEmitter staleEmitter = mock(SseEmitter.class);
        SseEmitter healthyEmitter = mock(SseEmitter.class);
        manager.register(6L, staleEmitter);
        manager.register(7L, healthyEmitter);
        doThrow(new IllegalStateException(
            "Failed to send [event]", new AsyncRequestNotUsableException("Response not usable after response errors.")))
            .when(staleEmitter)
            .send(any(SseEmitter.SseEventBuilder.class));
        doNothing().when(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatNoException().isThrownBy(() -> manager.sendToAll("payload"));

        assertThat(emitterMap(manager)).doesNotContainKey(6L).containsKey(7L);
        verify(staleEmitter, never()).complete();
        verify(healthyEmitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, SseEmitter> emitterMap(SseConnectionManager manager) {
        try {
            Field field = SseConnectionManager.class.getDeclaredField("emitters");
            field.setAccessible(true);
            return (ConcurrentHashMap<Long, SseEmitter>) field.get(manager);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
