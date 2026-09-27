package com.csd.occ.diagnosis.infrastructure.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GeminiDiagnosisConfig {
    @Bean(destroyMethod = "close")
    Client diagnosisGeminiClient(DiagnosisGeminiProperties properties) {
        HttpRetryOptions retry = HttpRetryOptions.builder().attempts(properties.retryAttempts()).build();
        HttpOptions http = HttpOptions.builder().timeout(properties.requestTimeoutMillis()).retryOptions(retry).build();
        return Client.builder().apiKey(properties.requiredApiKey()).httpOptions(http).build();
    }

    @Bean
    GoogleGenAiChatModel diagnosisGeminiChatModel(Client diagnosisGeminiClient, DiagnosisGeminiProperties properties) {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(properties.model())
                .temperature(properties.temperature()).thinkingLevel(properties.thinkingLevel())
                .maxOutputTokens(properties.maxOutputTokens()).includeThoughts(false).candidateCount(1).build();
        return GoogleGenAiChatModel.builder().genAiClient(diagnosisGeminiClient).options(options).build();
    }

    @Bean
    ChatClient diagnosisChatClient(GoogleGenAiChatModel diagnosisGeminiChatModel) {
        return ChatClient.create(diagnosisGeminiChatModel);
    }
}
