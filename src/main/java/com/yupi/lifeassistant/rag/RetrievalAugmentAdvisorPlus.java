package com.yupi.lifeassistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
public class RetrievalAugmentAdvisorPlus {

    @Bean
    public Advisor myRetrievalAugmentAdvisor(VectorStore pgVectorStore, ChatModel dashboardChatModel) {
        //检索前Query Transformation，多个Query Transformer按List的添加顺序执行，详见RetrievalAugmentationAdvisor的before方法
        /*for (var queryTransformer : this.queryTransformers) {
            transformedQuery = queryTransformer.apply(transformedQuery);
        }*/
        List<QueryTransformer> queryTransformers = new ArrayList<>();
        ChatClient.Builder chatClientBuilder = ChatClient.builder(dashboardChatModel);
        //检索前将对话上下文压缩一下
        QueryTransformer compressionQueryTransformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        //检索前将对话上下文重写一下，使其更加简洁专业
        QueryTransformer rewriteQueryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        //检索前将query拓展成多个问题，提升文档召回率
        QueryExpander queryExpander = MultiQueryExpander.builder()
                        .chatClientBuilder(chatClientBuilder)
                        .numberOfQueries(3)//拓展出几个问题
                        .build();

        queryTransformers.add(compressionQueryTransformer);
        queryTransformers.add(rewriteQueryTransformer);

        //检索中
        //VectorStoreDocumentRetriever基于向量数据库进行文档检索
        VectorStoreDocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .topK(3)//topK
                .similarityThreshold(0.5)//相似度阈值
                .vectorStore(pgVectorStore)//向量数据库
                .build();
        //DocumentJoiner将基于多次查询和多个数据源检索的文档合并为单一文档集合，应该适合于配合MultiQueryExpander使用
        DocumentJoiner documentJoiner = new ConcatenationDocumentJoiner();

        //目前SoringAI检索后处理还没有实现类
        //TODO 文档检索后处理，例如，根据文档与查询的相关性进行排名，删除无关或冗余的文档，或压缩每份文档的内容以减少杂音和冗余。

        //允许空上下文
        ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(queryTransformers)
                .documentRetriever(documentRetriever)
                .documentJoiner(documentJoiner)
                .queryAugmenter(queryAugmenter)
                .build();
    }
}
