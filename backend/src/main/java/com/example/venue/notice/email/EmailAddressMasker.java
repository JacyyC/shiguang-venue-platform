package com.example.venue.notice.email;

import org.springframework.stereotype.Component;

@Component
public final class EmailAddressMasker {
    public String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) {
            return "***";
        }

        String local = maskLabel(address.substring(0, at));
        String[] domainLabels = address.substring(at + 1).split("\\.", -1);
        StringBuilder domain = new StringBuilder();
        for (int index = 0; index < domainLabels.length; index++) {
            if (index > 0) {
                domain.append('.');
            }
            String label = domainLabels[index];
            boolean visibleSuffix = index == domainLabels.length - 1 && label.length() > 2;
            domain.append(visibleSuffix ? label : maskLabel(label));
        }
        return local + "@" + domain;
    }

    private String maskLabel(String label) {
        if (label.length() <= 2) {
            return "***";
        }
        return label.charAt(0) + "***" + label.charAt(label.length() - 1);
    }
}
