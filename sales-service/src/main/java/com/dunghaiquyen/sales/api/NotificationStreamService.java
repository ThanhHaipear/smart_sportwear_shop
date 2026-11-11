package com.dunghaiquyen.sales.api;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationStreamService {
    private static final long TIMEOUT_MS=15*60*1000L;
    private final ConcurrentHashMap<UUID,CopyOnWriteArrayList<SseEmitter>> clients=new ConcurrentHashMap<>();
    public SseEmitter register(UUID user){
        SseEmitter emitter=new SseEmitter(TIMEOUT_MS); clients.computeIfAbsent(user,key->new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup=()->remove(user,emitter); emitter.onCompletion(cleanup);emitter.onTimeout(cleanup);emitter.onError(error->cleanup.run());
        try{emitter.send(SseEmitter.event().name("ready").data("ok").reconnectTime(3000));}catch(IOException error){cleanup.run();}
        return emitter;
    }
    public void changed(UUID user,String event){
        for(SseEmitter emitter:clients.getOrDefault(user,new CopyOnWriteArrayList<>()))try{emitter.send(SseEmitter.event().name(event).data("refresh"));}catch(IOException error){remove(user,emitter);}
    }
    private void remove(UUID user,SseEmitter emitter){var list=clients.get(user);if(list!=null){list.remove(emitter);if(list.isEmpty())clients.remove(user,list);}}
}
