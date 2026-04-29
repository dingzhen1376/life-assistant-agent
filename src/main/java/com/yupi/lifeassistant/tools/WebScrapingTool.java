package com.yupi.lifeassistant.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

public class WebScrapingTool {

    @Tool(description = "Scrape readable text from a public web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
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
        } catch (IOException e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
