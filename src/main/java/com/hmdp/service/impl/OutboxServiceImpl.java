package com.hmdp.service.impl;

import com.hmdp.entity.MessageOutbox;
import com.hmdp.mapper.MessageOutboxMapper;
import com.hmdp.service.IOutboxService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class OutboxServiceImpl implements IOutboxService {

    @Resource
    private MessageOutboxMapper messageOutboxMapper;

    @Override
    public void saveEvent(String bizType, String bizKey, String payload) {
        LocalDateTime now = LocalDateTime.now();
        MessageOutbox outbox = new MessageOutbox()
                .setBizType(bizType)
                .setBizKey(bizKey)
                .setPayload(payload)
                .setStatus(0)
                .setRetryCount(0)
                .setNextTime(now)
                .setCreateTime(now)
                .setUpdateTime(now);
        messageOutboxMapper.insert(outbox);
    }
}
