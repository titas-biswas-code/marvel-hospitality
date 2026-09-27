package com.marvel.hospitality.notification.domain;

/** The template a status event selected and the customer-facing text rendered from it. */
public record RenderedNotification(NotificationTemplate template, String text) {
}
