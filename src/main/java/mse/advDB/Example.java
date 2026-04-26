package mse.advDB;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.neo4j.driver.Values.parameters;

public class Example {

    private static final String NEO4J_USER = "neo4j";
    private static final String NEO4J_PASSWORD = "test";

    public static void main(String[] args) throws Exception {
        String jsonPath = getenv("JSON_FILE", "/file.jsonl");
        String neo4jIp = getenv("NEO4J_IP", "localhost");

        int maxArticles = Integer.parseInt(getenv("MAX_NODES", "10000"));
        int batchSize = Integer.parseInt(getenv("BATCH_SIZE", "500"));

        Instant start = Instant.now();

        System.out.println("=== DBLP LOADER START ===");
        System.out.println("Start time: " + start);
        System.out.println("JSON file: " + jsonPath);
        System.out.println("Neo4j IP: " + neo4jIp);
        System.out.println("Max articles to read: " + maxArticles);
        System.out.println("Batch size: " + batchSize);

        Driver driver = GraphDatabase.driver(
                "bolt://" + neo4jIp + ":7687",
                AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD)
        );

        waitForNeo4j(driver);

        try (Session session = driver.session()) {
            createConstraints(session);

            System.out.println("Pass 1/2: loading ARTICLE, AUTHOR and AUTHORED...");
            long inputArticles = loadArticlesAndAuthors(session, jsonPath, maxArticles, batchSize);

            System.out.println("Pass 2/2: loading CITES relationships...");
            loadCitations(session, jsonPath, maxArticles, batchSize);

            long articleCount = count(session, "MATCH (a:ARTICLE) RETURN count(a) AS c");
            long authorCount = count(session, "MATCH (a:AUTHOR) RETURN count(a) AS c");
            long authoredCount = count(session, "MATCH ()-[r:AUTHORED]->() RETURN count(r) AS c");
            long citesCount = count(session, "MATCH ()-[r:CITES]->() RETURN count(r) AS c");

            Instant end = Instant.now();
            long durationSeconds = Duration.between(start, end).getSeconds();

            System.out.println("=== DBLP LOADER END ===");
            System.out.println("End time: " + end);
            System.out.println("Duration seconds: " + durationSeconds);
            System.out.println("Input article lines read: " + inputArticles);
            System.out.println("Articles loaded: " + articleCount);
            System.out.println("Authors loaded: " + authorCount);
            System.out.println("Total nodes loaded: " + (articleCount + authorCount));
            System.out.println("AUTHORED relationships loaded: " + authoredCount);
            System.out.println("CITES relationships loaded: " + citesCount);
        } finally {
            driver.close();
        }
    }

    private static void waitForNeo4j(Driver driver) throws InterruptedException {
        boolean connected = false;

        while (!connected) {
            try {
                System.out.println("Waiting for Neo4j...");
                Thread.sleep(5000);
                driver.verifyConnectivity();
                connected = true;
                System.out.println("Connected to Neo4j.");
            } catch (Exception e) {
                System.out.println("Neo4j not ready yet.");
            }
        }
    }

    private static void createConstraints(Session session) {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT article_id IF NOT EXISTS " +
                    "FOR (a:ARTICLE) REQUIRE a._id IS UNIQUE").consume();

            tx.run("CREATE CONSTRAINT author_id IF NOT EXISTS " +
                    "FOR (a:AUTHOR) REQUIRE a._id IS UNIQUE").consume();

            return null;
        });

        System.out.println("Constraints created or already existing.");
    }

    private static long loadArticlesAndAuthors(
            Session session,
            String jsonPath,
            int maxArticles,
            int batchSize
    ) throws IOException {

        ArrayList<Map<String, Object>> articleBatch = new ArrayList<>();
        ArrayList<Map<String, Object>> authorBatch = new ArrayList<>();
        ArrayList<Map<String, Object>> authoredBatch = new ArrayList<>();

        long articlesRead = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(jsonPath))) {
            String line;

            while ((line = br.readLine()) != null) {
                if (maxArticles > 0 && articlesRead >= maxArticles) {
                    break;
                }

                JsonObject article = parseJsonObject(line);
                String articleId = getString(article, "id");

                if (articleId.isEmpty()) {
                    continue;
                }

                String title = getString(article, "title");

                Map<String, Object> articleRow = new HashMap<>();
                articleRow.put("id", articleId);
                articleRow.put("title", title);
                articleBatch.add(articleRow);

                if (article.containsKey("authors") && !article.isNull("authors")) {
                    JsonArray authors = article.getJsonArray("authors");

                    for (int i = 0; i < authors.size(); i++) {
                        JsonObject author = authors.getJsonObject(i);

                        String authorId = buildAuthorId(author);
                        String authorName = getString(author, "name");

                        if (authorId.isEmpty() || authorName.isEmpty()) {
                            continue;
                        }

                        Map<String, Object> authorRow = new HashMap<>();
                        authorRow.put("id", authorId);
                        authorRow.put("name", authorName);
                        authorBatch.add(authorRow);

                        Map<String, Object> authoredRow = new HashMap<>();
                        authoredRow.put("authorId", authorId);
                        authoredRow.put("articleId", articleId);
                        authoredBatch.add(authoredRow);
                    }
                }

                articlesRead++;

                if (articleBatch.size() >= batchSize) {
                    flushArticles(session, articleBatch);
                    flushAuthors(session, authorBatch);
                    flushAuthored(session, authoredBatch);

                    if (articlesRead % (batchSize * 10L) == 0) {
                        System.out.println("Pass 1 progress - articles read: " + articlesRead);
                    }
                }
            }
        }

        flushArticles(session, articleBatch);
        flushAuthors(session, authorBatch);
        flushAuthored(session, authoredBatch);

        return articlesRead;
    }

    private static void loadCitations(
            Session session,
            String jsonPath,
            int maxArticles,
            int batchSize
    ) throws IOException {

        ArrayList<Map<String, Object>> citationBatch = new ArrayList<>();

        long articlesRead = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(jsonPath))) {
            String line;

            while ((line = br.readLine()) != null) {
                if (maxArticles > 0 && articlesRead >= maxArticles) {
                    break;
                }

                JsonObject article = parseJsonObject(line);
                String sourceId = getString(article, "id");

                if (sourceId.isEmpty()) {
                    continue;
                }

                if (article.containsKey("references") && !article.isNull("references")) {
                    JsonArray references = article.getJsonArray("references");

                    for (int i = 0; i < references.size(); i++) {
                        String targetId = references.getString(i, "");

                        if (targetId.isEmpty()) {
                            continue;
                        }

                        Map<String, Object> citationRow = new HashMap<>();
                        citationRow.put("sourceId", sourceId);
                        citationRow.put("targetId", targetId);
                        citationBatch.add(citationRow);
                    }
                }

                articlesRead++;

                if (citationBatch.size() >= batchSize) {
                    flushCitations(session, citationBatch);

                    if (articlesRead % (batchSize * 10L) == 0) {
                        System.out.println("Pass 2 progress - articles read: " + articlesRead);
                    }
                }
            }
        }

        flushCitations(session, citationBatch);
    }

    private static void flushArticles(Session session, ArrayList<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        session.writeTransaction(tx -> {
            tx.run(
                    "UNWIND $rows AS row " +
                            "MERGE (a:ARTICLE {_id: row.id}) " +
                            "SET a.title = row.title",
                    parameters("rows", rows)
            ).consume();

            return null;
        });

        rows.clear();
    }

    private static void flushAuthors(Session session, ArrayList<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        session.writeTransaction(tx -> {
            tx.run(
                    "UNWIND $rows AS row " +
                            "MERGE (a:AUTHOR {_id: row.id}) " +
                            "SET a.name = row.name",
                    parameters("rows", rows)
            ).consume();

            return null;
        });

        rows.clear();
    }

    private static void flushAuthored(Session session, ArrayList<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        session.writeTransaction(tx -> {
            tx.run(
                    "UNWIND $rows AS row " +
                            "MATCH (author:AUTHOR {_id: row.authorId}) " +
                            "MATCH (article:ARTICLE {_id: row.articleId}) " +
                            "MERGE (author)-[:AUTHORED]->(article)",
                    parameters("rows", rows)
            ).consume();

            return null;
        });

        rows.clear();
    }

    private static void flushCitations(Session session, ArrayList<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }

        session.writeTransaction(tx -> {
            tx.run(
                    "UNWIND $rows AS row " +
                            "MATCH (source:ARTICLE {_id: row.sourceId}) " +
                            "MATCH (target:ARTICLE {_id: row.targetId}) " +
                            "MERGE (source)-[:CITES]->(target)",
                    parameters("rows", rows)
            ).consume();

            return null;
        });

        rows.clear();
    }

    private static long count(Session session, String query) {
        return session.readTransaction(tx -> {
            Result result = tx.run(query);
            return result.single().get("c").asLong();
        });
    }

    private static JsonObject parseJsonObject(String line) {
        try (JsonReader reader = Json.createReader(new StringReader(line))) {
            return reader.readObject();
        }
    }

    private static String getString(JsonObject object, String key) {
        if (!object.containsKey(key) || object.isNull(key)) {
            return "";
        }

        try {
            return object.getString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildAuthorId(JsonObject author) {
        String rawId = getString(author, "id");

        if (!rawId.trim().isEmpty()) {
            return rawId.trim();
        }

        String name = getString(author, "name").trim();

        if (name.isEmpty()) {
            return "";
        }

        return "generated-author-id:" + name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String getenv(String key, String defaultValue) {
        String value = System.getenv(key);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value.trim();
    }
}