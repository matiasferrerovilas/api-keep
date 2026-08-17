package api.m2.file.record.events;

import api.m2.file.enums.EventType;

public record EventWrapper<T>(EventType eventType, T message) {
}
