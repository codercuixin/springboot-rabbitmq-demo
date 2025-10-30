package com.example.rabbitmq.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {
    
    private String id;
    private String content;
    private LocalDateTime timestamp;
    
    public Message(String id, String content) {
        this.id = id;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }
}

