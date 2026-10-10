package com.example.venue.notice.email;

import java.util.Map;

public interface EmailTemplateRenderer {
    RenderedEmail render(EmailTemplate template, Map<String, String> model);
}
