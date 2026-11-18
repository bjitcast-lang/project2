package com.hmdp.service;

/**
 * 本地消息表服务：业务事务内写事件，后台任务可靠投递。
 */
public interface IOutboxService {

    void saveEvent(String bizType, String bizKey, String payload);
}
