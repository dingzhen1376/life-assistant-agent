package com.yupi.lifeassistant.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.net.InetAddress;
import java.net.URI;
import java.io.IOException;

public class WebScrapingTool {

    @Tool(description = "Scrape readable text from a public web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            validatePublicHttpUrl(url);
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 LifeAssistantAgent")
                    .timeout(10_000)
                    .get();
            String title = document.title();
            String text = document.body() == null ? document.text() : document.body().text();
            if (text.length() > 6000) {
                text = text.substring(0, 6000);
            }
            return "Title: " + title + "\nContent: " + text;
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }

    private void validatePublicHttpUrl(String url) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Only public http/https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL host is required");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("Private, local, or multicast hosts are blocked");
            }
        }
    }
}
