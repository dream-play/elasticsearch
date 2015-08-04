/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.queries.BoostingQuery;
import org.apache.lucene.queries.ExtendedCommonTermsQuery;
import org.apache.lucene.queries.TermsQuery;
import org.apache.lucene.search.*;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.search.spans.*;
import org.apache.lucene.spatial.prefix.IntersectsPrefixTreeFilter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.automaton.TooComplexToDeterminizeException;
import org.elasticsearch.action.termvectors.*;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.lucene.search.MoreLikeThisQuery;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.lucene.search.function.BoostScoreFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.common.lucene.search.function.WeightFactorFunction;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.core.NumberFieldMapper;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.index.search.geo.GeoDistanceRangeQuery;
import org.elasticsearch.index.search.geo.GeoPolygonQuery;
import org.elasticsearch.index.search.geo.InMemoryGeoBoundingBoxQuery;
import org.elasticsearch.index.search.morelikethis.MoreLikeThisFetchService;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.elasticsearch.common.io.Streams.copyToBytesFromClasspath;
import static org.elasticsearch.common.io.Streams.copyToStringFromClasspath;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBooleanSubQuery;
import static org.hamcrest.Matchers.*;

public class SimpleIndexQueryParserTests extends ESSingleNodeTestCase {

    private IndexQueryParserService queryParser;

    @Before
    public void setup() throws IOException {
        Settings settings = Settings.settingsBuilder()
                .put("index.cache.filter.type", "none")
                .put("name", "SimpleIndexQueryParserTests")
                .build();
        IndexService indexService = createIndex("test", settings);
        MapperService mapperService = indexService.mapperService();

        String mapping = copyToStringFromClasspath("/org/elasticsearch/index/query/mapping.json");
        mapperService.merge("person", new CompressedXContent(mapping), true, false);
        ParsedDocument doc = mapperService.documentMapper("person").parse("test", "person", "1", new BytesArray(copyToBytesFromClasspath("/org/elasticsearch/index/query/data.json")));
        assertNotNull(doc.dynamicMappingsUpdate());
        client().admin().indices().preparePutMapping("test").setType("person").setSource(doc.dynamicMappingsUpdate().toString()).get();

        queryParser = indexService.queryParserService();
    }

    private IndexQueryParserService queryParser() throws IOException {
        return this.queryParser;
    }

    private BytesRef longToPrefixCoded(long val, int shift) {
        BytesRefBuilder bytesRef = new BytesRefBuilder();
        NumericUtils.longToPrefixCoded(val, shift, bytesRef);
        return bytesRef.get();
    }

    @Test
    public void testQueryStringBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(queryStringQuery("test").defaultField("content").phraseSlop(1)).query();

        assertThat(parsedQuery, instanceOf(TermQuery.class));
        TermQuery termQuery = (TermQuery) parsedQuery;
        assertThat(termQuery.getTerm(), equalTo(new Term("content", "test")));
    }

    @Test
    public void testQueryString() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(TermQuery.class));
        TermQuery termQuery = (TermQuery) parsedQuery;
        assertThat(termQuery.getTerm(), equalTo(new Term("content", "test")));
    }

    @Test
    public void testQueryStringBoostsBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        QueryStringQueryBuilder builder = queryStringQuery("field:boosted^2");
        Query parsedQuery = queryParser.parse(builder).query();
        assertThat(parsedQuery, instanceOf(TermQuery.class));
        assertThat(((TermQuery) parsedQuery).getTerm(), equalTo(new Term("field", "boosted")));
        assertThat(parsedQuery.getBoost(), equalTo(2.0f));
        builder.boost(2.0f);
        parsedQuery = queryParser.parse(builder).query();
        assertThat(parsedQuery.getBoost(), equalTo(4.0f));

        builder = queryStringQuery("((field:boosted^2) AND (field:foo^1.5))^3");
        parsedQuery = queryParser.parse(builder).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 0).getTerm(), equalTo(new Term("field", "boosted")));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 0).getBoost(), equalTo(2.0f));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 1).getTerm(), equalTo(new Term("field", "foo")));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 1).getBoost(), equalTo(1.5f));
        assertThat(parsedQuery.getBoost(), equalTo(3.0f));
        builder.boost(2.0f);
        parsedQuery = queryParser.parse(builder).query();
        assertThat(parsedQuery.getBoost(), equalTo(6.0f));
    }

    @Test
    public void testQueryStringFields1Builder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(queryStringQuery("test").field("content").field("name").useDisMax(false)).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery bQuery = (BooleanQuery) parsedQuery;
        assertThat(bQuery.clauses().size(), equalTo(2));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 0).getTerm(), equalTo(new Term("content", "test")));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 1).getTerm(), equalTo(new Term("name", "test")));
    }

    @Test
    public void testQueryStringFields1() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-fields1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery bQuery = (BooleanQuery) parsedQuery;
        assertThat(bQuery.clauses().size(), equalTo(2));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 0).getTerm(), equalTo(new Term("content", "test")));
        assertThat(assertBooleanSubQuery(parsedQuery, TermQuery.class, 1).getTerm(), equalTo(new Term("name", "test")));
    }

    @Test
    public void testQueryStringFieldsMatch() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-fields-match.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery bQuery = (BooleanQuery) parsedQuery;
        assertThat(bQuery.clauses().size(), equalTo(2));
        assertEquals(Sets.newHashSet(new Term("name.first", "test"), new Term("name.last", "test")),
                Sets.newHashSet(assertBooleanSubQuery(parsedQuery, TermQuery.class, 0).getTerm(),
                        assertBooleanSubQuery(parsedQuery, TermQuery.class, 1).getTerm()));
    }

    @Test
    public void testQueryStringFields2Builder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(queryStringQuery("test").field("content").field("name").useDisMax(true)).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        List<Query> disjuncts = disMaxQuery.getDisjuncts();
        assertThat(((TermQuery) disjuncts.get(0)).getTerm(), equalTo(new Term("content", "test")));
        assertThat(((TermQuery) disjuncts.get(1)).getTerm(), equalTo(new Term("name", "test")));
    }

    @Test
    public void testQueryStringFields2() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-fields2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        List<Query> disjuncts = disMaxQuery.getDisjuncts();
        assertThat(((TermQuery) disjuncts.get(0)).getTerm(), equalTo(new Term("content", "test")));
        assertThat(((TermQuery) disjuncts.get(1)).getTerm(), equalTo(new Term("name", "test")));
    }

    @Test
    public void testQueryStringFields3Builder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(queryStringQuery("test").field("content", 2.2f).field("name").useDisMax(true)).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        List<Query> disjuncts = disMaxQuery.getDisjuncts();
        assertThat(((TermQuery) disjuncts.get(0)).getTerm(), equalTo(new Term("content", "test")));
        assertThat((double) disjuncts.get(0).getBoost(), closeTo(2.2, 0.01));
        assertThat(((TermQuery) disjuncts.get(1)).getTerm(), equalTo(new Term("name", "test")));
        assertThat((double) disjuncts.get(1).getBoost(), closeTo(1, 0.01));
    }

    @Test
    public void testQueryStringFields3() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-fields3.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        List<Query> disjuncts = disMaxQuery.getDisjuncts();
        assertThat(((TermQuery) disjuncts.get(0)).getTerm(), equalTo(new Term("content", "test")));
        assertThat((double) disjuncts.get(0).getBoost(), closeTo(2.2, 0.01));
        assertThat(((TermQuery) disjuncts.get(1)).getTerm(), equalTo(new Term("name", "test")));
        assertThat((double) disjuncts.get(1).getBoost(), closeTo(1, 0.01));
    }

    @Test
    public void testQueryStringTimezone() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-timezone.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(TermRangeQuery.class));

        try {
            queryParser.parse(copyToStringFromClasspath("/org/elasticsearch/index/query/query-timezone-incorrect.json"));
            fail("we expect a QueryParsingException as we are providing an unknown time_zome");
        } catch (QueryParsingException e) {
            // We expect this one
        }
    }

    @Test
    public void testQueryStringRegexp() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-regexp-max-determinized-states.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(RegexpQuery.class));
        RegexpQuery regexpQuery = (RegexpQuery) parsedQuery;
        assertTrue(regexpQuery.toString().contains("/foo*bar/"));
    }

    @Test
    public void testQueryStringRegexpTooManyDeterminizedStates() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-regexp-too-many-determinized-states.json");
        try {
            queryParser.parse(query).query();
            fail("did not hit exception");
        } catch (QueryParsingException qpe) {
            // expected
            assertTrue(qpe.getCause() instanceof TooComplexToDeterminizeException);
        }
    }

    @Test
    public void testMatchAllBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(matchAllQuery().boost(1.2f)).query();
        assertThat(parsedQuery, instanceOf(MatchAllDocsQuery.class));
        MatchAllDocsQuery matchAllDocsQuery = (MatchAllDocsQuery) parsedQuery;
        assertThat((double) matchAllDocsQuery.getBoost(), closeTo(1.2, 0.01));
    }

    @Test
    public void testMatchAll() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/matchAll.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(MatchAllDocsQuery.class));
        MatchAllDocsQuery matchAllDocsQuery = (MatchAllDocsQuery) parsedQuery;
        assertThat((double) matchAllDocsQuery.getBoost(), closeTo(1.2, 0.01));
    }

    @Test
    public void testMatchAllEmpty1() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/match_all_empty1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, equalTo(Queries.newMatchAllQuery()));
        assertThat(parsedQuery, not(sameInstance(Queries.newMatchAllQuery())));
    }

    @Test
    public void testMatchAllEmpty2() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/match_all_empty2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, equalTo(Queries.newMatchAllQuery()));
        assertThat(parsedQuery, not(sameInstance(Queries.newMatchAllQuery())));

    }

    @Test
    public void testStarColonStar() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/starColonStar.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(MatchAllDocsQuery.class));
    }

    @Test
    public void testDisMaxBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(disMaxQuery().boost(1.2f).tieBreaker(0.7f).add(termQuery("name.first", "first")).add(termQuery("name.last", "last"))).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disjunctionMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        assertThat((double) disjunctionMaxQuery.getBoost(), closeTo(1.2, 0.01));

        List<Query> disjuncts = disjunctionMaxQuery.getDisjuncts();
        assertThat(disjuncts.size(), equalTo(2));

        Query firstQ = disjuncts.get(0);
        assertThat(firstQ, instanceOf(TermQuery.class));
        assertThat(((TermQuery) firstQ).getTerm(), equalTo(new Term("name.first", "first")));

        Query secondsQ = disjuncts.get(1);
        assertThat(secondsQ, instanceOf(TermQuery.class));
        assertThat(((TermQuery) secondsQ).getTerm(), equalTo(new Term("name.last", "last")));
    }

    @Test
    public void testDisMax() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/disMax.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disjunctionMaxQuery = (DisjunctionMaxQuery) parsedQuery;
        assertThat((double) disjunctionMaxQuery.getBoost(), closeTo(1.2, 0.01));

        List<Query> disjuncts = disjunctionMaxQuery.getDisjuncts();
        assertThat(disjuncts.size(), equalTo(2));

        Query firstQ = disjuncts.get(0);
        assertThat(firstQ, instanceOf(TermQuery.class));
        assertThat(((TermQuery) firstQ).getTerm(), equalTo(new Term("name.first", "first")));

        Query secondsQ = disjuncts.get(1);
        assertThat(secondsQ, instanceOf(TermQuery.class));
        assertThat(((TermQuery) secondsQ).getTerm(), equalTo(new Term("name.last", "last")));
    }

    @Test
    public void testDisMax2() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/disMax2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
        DisjunctionMaxQuery disjunctionMaxQuery = (DisjunctionMaxQuery) parsedQuery;

        List<Query> disjuncts = disjunctionMaxQuery.getDisjuncts();
        assertThat(disjuncts.size(), equalTo(1));

        PrefixQuery firstQ = (PrefixQuery) disjuncts.get(0);
        // since age is automatically registered in data, we encode it as numeric
        assertThat(firstQ.getPrefix(), equalTo(new Term("name.first", "sh")));
        assertThat((double) firstQ.getBoost(), closeTo(1.2, 0.00001));
    }

    @Test
    public void testTermQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(termQuery("age", 34).buildAsBytes()).query();
        TermQuery fieldQuery = unwrapTermQuery(parsedQuery);
        assertThat(fieldQuery.getTerm().bytes(), equalTo(indexedValueForSearch(34l)));
    }

    @Test
    public void testTermQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term.json");
        TermQuery fieldQuery = unwrapTermQuery(queryParser.parse(query).query());
        assertThat(fieldQuery.getTerm().bytes(), equalTo(indexedValueForSearch(34l)));
    }

    @Test(expected = QueryParsingException.class)
    public void testTermQueryArrayInvalid() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-array-invalid.json");
        unwrapTermQuery(queryParser.parse(query).query());
    }

    private static TermQuery unwrapTermQuery(Query q) {
        assertThat(q, instanceOf(TermQuery.class));
        return (TermQuery) q;
    }

    @Test
    public void testFuzzyQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(fuzzyQuery("name.first", "sh").buildAsBytes()).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        FuzzyQuery fuzzyQuery = (FuzzyQuery) parsedQuery;
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term("name.first", "sh")));
    }

    @Test
    public void testFuzzyQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/fuzzy.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        FuzzyQuery fuzzyQuery = (FuzzyQuery) parsedQuery;
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term("name.first", "sh")));
        assertThat(fuzzyQuery.getRewriteMethod(), instanceOf(MultiTermQuery.TopTermsBlendedFreqScoringRewrite.class));
    }

    @Test
    public void testFuzzyQueryWithFieldsBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(fuzzyQuery("name.first", "sh").fuzziness(Fuzziness.ONE).prefixLength(1).boost(2.0f).buildAsBytes()).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        FuzzyQuery fuzzyQuery = (FuzzyQuery) parsedQuery;
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term("name.first", "sh")));
        assertThat(fuzzyQuery.getMaxEdits(), equalTo(FuzzyQuery.floatToEdits(0.1f, "sh".length())));
        assertThat(fuzzyQuery.getPrefixLength(), equalTo(1));
        assertThat(fuzzyQuery.getBoost(), equalTo(2.0f));
    }

    @Test
    public void testFuzzyQueryWithFields() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/fuzzy-with-fields.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        FuzzyQuery fuzzyQuery = (FuzzyQuery) parsedQuery;
        assertThat(fuzzyQuery.getTerm(), equalTo(new Term("name.first", "sh")));
        assertThat(fuzzyQuery.getMaxEdits(), equalTo(Fuzziness.AUTO.asDistance("sh")));
        assertThat(fuzzyQuery.getPrefixLength(), equalTo(1));
        assertThat(fuzzyQuery.getBoost(), equalTo(2.0f));
    }

    @Test
    public void testFuzzyQueryWithFields2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/fuzzy-with-fields2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(NumericRangeQuery.class));
        NumericRangeQuery fuzzyQuery = (NumericRangeQuery) parsedQuery;
        assertThat(fuzzyQuery.getMin().longValue(), equalTo(7l));
        assertThat(fuzzyQuery.getMax().longValue(), equalTo(17l));
    }

    @Test
    public void testTermWithBoostQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();

        Query parsedQuery = queryParser.parse(termQuery("age", 34).boost(2.0f)).query();
        TermQuery fieldQuery = unwrapTermQuery(parsedQuery);
        assertThat(fieldQuery.getTerm().bytes(), equalTo(indexedValueForSearch(34l)));
        assertThat((double) parsedQuery.getBoost(), closeTo(2.0, 0.01));
    }

    private BytesRef indexedValueForSearch(long value) {
        BytesRefBuilder bytesRef = new BytesRefBuilder();
        NumericUtils.longToPrefixCoded(value, 0, bytesRef); // 0 because of
                                                            // exact
                                                            // match
        return bytesRef.get();
    }

    @Test
    public void testTermWithBoostQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-with-boost.json");
        Query parsedQuery = queryParser.parse(query).query();
        TermQuery fieldQuery = unwrapTermQuery(parsedQuery);
        assertThat(fieldQuery.getTerm().bytes(), equalTo(indexedValueForSearch(34l)));
        assertThat((double) parsedQuery.getBoost(), closeTo(2.0, 0.01));
    }

    @Test
    public void testPrefixQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(prefixQuery("name.first", "sh")).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("name.first", "sh")));
    }

    @Test
    public void testPrefixQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/prefix.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("name.first", "sh")));
    }

    @Test
    public void testPrefixBoostQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/prefix-boost.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("name.first", "sh")));
        assertThat((double) prefixQuery.getBoost(), closeTo(1.2, 0.00001));
    }

    @Test
    public void testPrefiFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), prefixQuery("name.first", "sh"))).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new PrefixQuery(new Term("name.first", "sh")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testPrefiFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/prefix-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new PrefixQuery(new Term("name.first", "sh")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testPrefixNamedFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/prefix-filter-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new PrefixQuery(new Term("name.first", "sh")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testPrefixQueryBoostQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(prefixQuery("name.first", "sh").boost(2.0f)).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("name.first", "sh")));
        assertThat((double) prefixQuery.getBoost(), closeTo(2.0, 0.01));
    }

    @Test
    public void testPrefixQueryBoostQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/prefix-with-boost.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("name.first", "sh")));
        assertThat((double) prefixQuery.getBoost(), closeTo(2.0, 0.01));
    }

    @Test
    public void testPrefixQueryWithUnknownField() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(prefixQuery("unknown", "sh")).query();
        assertThat(parsedQuery, instanceOf(PrefixQuery.class));
        PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
        assertThat(prefixQuery.getPrefix(), equalTo(new Term("unknown", "sh")));
        assertThat(prefixQuery.getRewriteMethod(), notNullValue());
    }

    @Test
    public void testRegexpQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(regexpQuery("name.first", "s.*y")).query();
        assertThat(parsedQuery, instanceOf(RegexpQuery.class));
        RegexpQuery regexpQuery = (RegexpQuery) parsedQuery;
        assertThat(regexpQuery.getField(), equalTo("name.first"));
    }

    @Test
    public void testRegexpQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(RegexpQuery.class));
        RegexpQuery regexpQuery = (RegexpQuery) parsedQuery;
        assertThat(regexpQuery.getField(), equalTo("name.first"));
    }

    @Test
    public void testRegexpQueryWithMaxDeterminizedStates() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-max-determinized-states.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(RegexpQuery.class));
        RegexpQuery regexpQuery = (RegexpQuery) parsedQuery;
        assertThat(regexpQuery.getField(), equalTo("name.first"));
    }

    @Test
    public void testRegexpFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new RegexpQuery(new Term("name.first", "s.*y")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testRegexpFilteredQueryWithMaxDeterminizedStates() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-filter-max-determinized-states.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new RegexpQuery(new Term("name.first", "s.*y")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testNamedRegexpFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-filter-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new RegexpQuery(new Term("name.first", "s.*y")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testRegexpWithFlagsFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-filter-flags.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new RegexpQuery(new Term("name.first", "s.*y")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testNamedAndCachedRegexpWithFlagsFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-filter-flags-named-cached.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new RegexpQuery(new Term("name.first", "s.*y")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testRegexpBoostQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/regexp-boost.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(RegexpQuery.class));
        RegexpQuery regexpQuery = (RegexpQuery) parsedQuery;
        assertThat(regexpQuery.getField(), equalTo("name.first"));
        assertThat(regexpQuery.getBoost(), equalTo(1.2f));
    }

    @Test
    public void testWildcardQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(wildcardQuery("name.first", "sh*")).query();
        assertThat(parsedQuery, instanceOf(WildcardQuery.class));
        WildcardQuery wildcardQuery = (WildcardQuery) parsedQuery;
        assertThat(wildcardQuery.getTerm(), equalTo(new Term("name.first", "sh*")));
    }

    @Test
    public void testWildcardQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/wildcard.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(WildcardQuery.class));
        WildcardQuery wildcardQuery = (WildcardQuery) parsedQuery;
        assertThat(wildcardQuery.getTerm(), equalTo(new Term("name.first", "sh*")));
    }

    @Test
    public void testWildcardBoostQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/wildcard-boost.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(WildcardQuery.class));
        WildcardQuery wildcardQuery = (WildcardQuery) parsedQuery;
        assertThat(wildcardQuery.getTerm(), equalTo(new Term("name.first", "sh*")));
        assertThat((double) wildcardQuery.getBoost(), closeTo(1.2, 0.00001));
    }

    @Test
    public void testRangeQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(rangeQuery("age").from(23).to(54).includeLower(true).includeUpper(false)).query();
        // since age is automatically registered in data, we encode it as numeric
        assertThat(parsedQuery, instanceOf(NumericRangeQuery.class));
        NumericRangeQuery rangeQuery = (NumericRangeQuery) parsedQuery;
        assertThat(rangeQuery.getField(), equalTo("age"));
        assertThat(rangeQuery.getMin().intValue(), equalTo(23));
        assertThat(rangeQuery.getMax().intValue(), equalTo(54));
        assertThat(rangeQuery.includesMin(), equalTo(true));
        assertThat(rangeQuery.includesMax(), equalTo(false));
    }

    @Test
    public void testRangeQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/range.json");
        Query parsedQuery = queryParser.parse(query).query();
        // since age is automatically registered in data, we encode it as numeric
        assertThat(parsedQuery, instanceOf(NumericRangeQuery.class));
        NumericRangeQuery rangeQuery = (NumericRangeQuery) parsedQuery;
        assertThat(rangeQuery.getField(), equalTo("age"));
        assertThat(rangeQuery.getMin().intValue(), equalTo(23));
        assertThat(rangeQuery.getMax().intValue(), equalTo(54));
        assertThat(rangeQuery.includesMin(), equalTo(true));
        assertThat(rangeQuery.includesMax(), equalTo(false));
    }

    @Test
    public void testRange2Query() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/range2.json");
        Query parsedQuery = queryParser.parse(query).query();
        // since age is automatically registered in data, we encode it as numeric
        assertThat(parsedQuery, instanceOf(NumericRangeQuery.class));
        NumericRangeQuery rangeQuery = (NumericRangeQuery) parsedQuery;
        assertThat(rangeQuery.getField(), equalTo("age"));
        assertThat(rangeQuery.getMin().intValue(), equalTo(23));
        assertThat(rangeQuery.getMax().intValue(), equalTo(54));
        assertThat(rangeQuery.includesMin(), equalTo(true));
        assertThat(rangeQuery.includesMax(), equalTo(false));
    }

    @Test
    public void testRangeFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), rangeQuery("age").from(23).to(54).includeLower(true).includeUpper(false))).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                NumericRangeQuery.newLongRange("age", 23L, 54L, true, false));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testRangeFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/range-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                NumericRangeQuery.newLongRange("age", 23L, 54L, true, false));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testRangeNamedFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/range-filter-named.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                NumericRangeQuery.newLongRange("age", 23L, 54L, true, false));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testBoolFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), boolQuery().must(termQuery("name.first", "shay1")).must(termQuery("name.first", "shay4")).mustNot(termQuery("name.first", "shay2")).should(termQuery("name.first", "shay3")))).query();

        BooleanQuery filter = new BooleanQuery();
        filter.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        filter.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        filter.add(new TermQuery(new Term("name.first", "shay2")), Occur.MUST_NOT);
        filter.add(new TermQuery(new Term("name.first", "shay3")), Occur.SHOULD);
        filter.setMinimumNumberShouldMatch(1);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                filter);
        assertEquals(expected, parsedQuery);
    }


    @Test
    public void testBoolFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/bool-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery filter = new BooleanQuery();
        filter.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        filter.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        filter.add(new TermQuery(new Term("name.first", "shay2")), Occur.MUST_NOT);
        filter.add(new TermQuery(new Term("name.first", "shay3")), Occur.SHOULD);
        filter.setMinimumNumberShouldMatch(1);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                filter);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testAndFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(matchAllQuery(), andQuery(termQuery("name.first", "shay1"), termQuery("name.first", "shay4")))).query();
        BooleanQuery and = new BooleanQuery();
        and.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        and.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        ConstantScoreQuery expected = new ConstantScoreQuery(and);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testAndFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/and-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery and = new BooleanQuery();
        and.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        and.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                and);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testAndNamedFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/and-filter-named.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery and = new BooleanQuery();
        and.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        and.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                and);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testAndFilteredQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/and-filter2.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery and = new BooleanQuery();
        and.add(new TermQuery(new Term("name.first", "shay1")), Occur.MUST);
        and.add(new TermQuery(new Term("name.first", "shay4")), Occur.MUST);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                and);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testOrFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(matchAllQuery(), orQuery(termQuery("name.first", "shay1"), termQuery("name.first", "shay4")))).query();
        BooleanQuery or = new BooleanQuery();
        or.add(new TermQuery(new Term("name.first", "shay1")), Occur.SHOULD);
        or.add(new TermQuery(new Term("name.first", "shay4")), Occur.SHOULD);
        ConstantScoreQuery expected = new ConstantScoreQuery(or);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testOrFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/or-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery or = new BooleanQuery();
        or.add(new TermQuery(new Term("name.first", "shay1")), Occur.SHOULD);
        or.add(new TermQuery(new Term("name.first", "shay4")), Occur.SHOULD);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                or);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testOrFilteredQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/or-filter2.json");
        Query parsedQuery = queryParser.parse(query).query();
        BooleanQuery or = new BooleanQuery();
        or.add(new TermQuery(new Term("name.first", "shay1")), Occur.SHOULD);
        or.add(new TermQuery(new Term("name.first", "shay4")), Occur.SHOULD);
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                or);
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testNotFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(matchAllQuery(), notQuery(termQuery("name.first", "shay1")))).query();
        ConstantScoreQuery expected = new ConstantScoreQuery(Queries.not(new TermQuery(new Term("name.first", "shay1"))));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testNotFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/not-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                Queries.not(new TermQuery(new Term("name.first", "shay1"))));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testNotFilteredQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/not-filter2.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                Queries.not(new TermQuery(new Term("name.first", "shay1"))));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testNotFilteredQuery3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/not-filter3.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                Queries.not(new TermQuery(new Term("name.first", "shay1"))));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testBoostingQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(boostingQuery(termQuery("field1", "value1"), termQuery("field1", "value2")).negativeBoost(0.2f)).query();
        assertThat(parsedQuery, instanceOf(BoostingQuery.class));
    }

    @Test
    public void testBoostingQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/boosting-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BoostingQuery.class));
    }

    @Test
    public void testQueryStringFuzzyNumeric() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(NumericRangeQuery.class));
        NumericRangeQuery fuzzyQuery = (NumericRangeQuery) parsedQuery;
        assertThat(fuzzyQuery.getMin().longValue(), equalTo(12l));
        assertThat(fuzzyQuery.getMax().longValue(), equalTo(12l));
    }

    @Test
    public void testBoolQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(boolQuery().must(termQuery("content", "test1")).must(termQuery("content", "test4")).mustNot(termQuery("content", "test2")).should(termQuery("content", "test3"))).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        BooleanClause[] clauses = booleanQuery.getClauses();

        assertThat(clauses.length, equalTo(4));

        assertThat(((TermQuery) clauses[0].getQuery()).getTerm(), equalTo(new Term("content", "test1")));
        assertThat(clauses[0].getOccur(), equalTo(BooleanClause.Occur.MUST));

        assertThat(((TermQuery) clauses[1].getQuery()).getTerm(), equalTo(new Term("content", "test4")));
        assertThat(clauses[1].getOccur(), equalTo(BooleanClause.Occur.MUST));

        assertThat(((TermQuery) clauses[2].getQuery()).getTerm(), equalTo(new Term("content", "test2")));
        assertThat(clauses[2].getOccur(), equalTo(BooleanClause.Occur.MUST_NOT));

        assertThat(((TermQuery) clauses[3].getQuery()).getTerm(), equalTo(new Term("content", "test3")));
        assertThat(clauses[3].getOccur(), equalTo(BooleanClause.Occur.SHOULD));
    }

    @Test
    public void testBoolQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/bool.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        BooleanClause[] clauses = booleanQuery.getClauses();

        assertThat(clauses.length, equalTo(4));

        assertThat(((TermQuery) clauses[0].getQuery()).getTerm(), equalTo(new Term("content", "test1")));
        assertThat(clauses[0].getOccur(), equalTo(BooleanClause.Occur.MUST));

        assertThat(((TermQuery) clauses[1].getQuery()).getTerm(), equalTo(new Term("content", "test4")));
        assertThat(clauses[1].getOccur(), equalTo(BooleanClause.Occur.MUST));

        assertThat(((TermQuery) clauses[2].getQuery()).getTerm(), equalTo(new Term("content", "test2")));
        assertThat(clauses[2].getOccur(), equalTo(BooleanClause.Occur.MUST_NOT));

        assertThat(((TermQuery) clauses[3].getQuery()).getTerm(), equalTo(new Term("content", "test3")));
        assertThat(clauses[3].getOccur(), equalTo(BooleanClause.Occur.SHOULD));
    }

    @Test
    public void testTermsQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(termsQuery("name.first", Lists.newArrayList("shay", "test"))).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        BooleanClause[] clauses = booleanQuery.getClauses();

        assertThat(clauses.length, equalTo(2));

        assertThat(((TermQuery) clauses[0].getQuery()).getTerm(), equalTo(new Term("name.first", "shay")));
        assertThat(clauses[0].getOccur(), equalTo(BooleanClause.Occur.SHOULD));

        assertThat(((TermQuery) clauses[1].getQuery()).getTerm(), equalTo(new Term("name.first", "test")));
        assertThat(clauses[1].getOccur(), equalTo(BooleanClause.Occur.SHOULD));
    }

    @Test
    public void testTermsQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/terms-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        BooleanClause[] clauses = booleanQuery.getClauses();

        assertThat(clauses.length, equalTo(2));

        assertThat(((TermQuery) clauses[0].getQuery()).getTerm(), equalTo(new Term("name.first", "shay")));
        assertThat(clauses[0].getOccur(), equalTo(BooleanClause.Occur.SHOULD));

        assertThat(((TermQuery) clauses[1].getQuery()).getTerm(), equalTo(new Term("name.first", "test")));
        assertThat(clauses[1].getOccur(), equalTo(BooleanClause.Occur.SHOULD));
    }

    @Test
    public void testTermsQueryWithMultipleFields() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = XContentFactory.jsonBuilder().startObject()
                .startObject("terms").array("foo", 123).array("bar", 456).endObject()
                .endObject().string();
        try {
            queryParser.parse(query).query();
            fail();
        } catch (QueryParsingException ex) {
            assertThat(ex.getMessage(), equalTo("[terms] query does not support multiple fields"));
        }
    }

    @Test
    public void testTermsFilterWithMultipleFields() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = XContentFactory.jsonBuilder().startObject()
                .startObject("filtered")
                .startObject("query").startObject("match_all").endObject().endObject()
                .startObject("filter").startObject("terms").array("foo", 123).array("bar", 456).endObject().endObject()
                .endObject().string();
        try {
            queryParser.parse(query).query();
            fail();
        } catch (QueryParsingException ex) {
            assertThat(ex.getMessage(), equalTo("[terms] query does not support multiple fields"));
        }
    }



    @Test
    public void testInQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(termsQuery("name.first", Lists.newArrayList("test1", "test2", "test3"))).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        BooleanClause[] clauses = booleanQuery.getClauses();

        assertThat(clauses.length, equalTo(3));

        assertThat(((TermQuery) clauses[0].getQuery()).getTerm(), equalTo(new Term("name.first", "test1")));
        assertThat(clauses[0].getOccur(), equalTo(BooleanClause.Occur.SHOULD));

        assertThat(((TermQuery) clauses[1].getQuery()).getTerm(), equalTo(new Term("name.first", "test2")));
        assertThat(clauses[1].getOccur(), equalTo(BooleanClause.Occur.SHOULD));

        assertThat(((TermQuery) clauses[2].getQuery()).getTerm(), equalTo(new Term("name.first", "test3")));
        assertThat(clauses[2].getOccur(), equalTo(BooleanClause.Occur.SHOULD));
    }

    @Test
    public void testFilteredQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), termQuery("name.last", "banon"))).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testFilteredQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/filtered-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testFilteredQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/filtered-query2.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testFilteredQuery3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/filtered-query3.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                NumericRangeQuery.newLongRange("age", 23L, 54L, true, false));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testFilteredQuery4() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/filtered-query4.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expectedQuery = new WildcardQuery(new Term("name.first", "sh*"));
        expectedQuery.setBoost(1.1f);
        Query expected = Queries.filtered(
                expectedQuery,
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testTermFilterQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testTermNamedFilterQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-filter-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testTermQueryParserShouldOnlyAllowSingleTerm() throws Exception {
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-filter-broken-multi-terms.json");
        assertQueryParsingFailureDueToMultipleTermsInTermFilter(query);
    }

    @Test
    public void testTermQueryParserShouldOnlyAllowSingleTermInAlternateFormat() throws Exception {
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/term-filter-broken-multi-terms-2.json");
        assertQueryParsingFailureDueToMultipleTermsInTermFilter(query);
    }

    private void assertQueryParsingFailureDueToMultipleTermsInTermFilter(String query) throws IOException {
        IndexQueryParserService queryParser = queryParser();
        try {
            queryParser.parse(query);
            fail("Expected Query Parsing Exception but did not happen");
        } catch (QueryParsingException e) {
            assertThat(e.getMessage(), containsString("[term] query does not support different field names, use [bool] query instead"));
        }
    }

    @Test
    public void testTermsFilterQueryBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), termsQuery("name.last", "banon", "kimchy"))).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermsQuery("name.last", new BytesRef("banon"), new BytesRef("kimchy")));
        assertEquals(expected, parsedQuery);
    }


    @Test
    public void testTermsFilterQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/terms-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermsQuery("name.last", new BytesRef("banon"), new BytesRef("kimchy")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testTermsWithNameFilterQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/terms-filter-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermsQuery("name.last", new BytesRef("banon"), new BytesRef("kimchy")));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testConstantScoreQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(constantScoreQuery(termQuery("name.last", "banon"))).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        assertThat(getTerm(constantScoreQuery.getQuery()), equalTo(new Term("name.last", "banon")));
    }

    @Test
    public void testConstantScoreQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/constantScore-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        assertThat(getTerm(constantScoreQuery.getQuery()), equalTo(new Term("name.last", "banon")));
    }

    @Test
    public void testCustomWeightFactorQueryBuilder_withFunctionScore() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(functionScoreQuery(termQuery("name.last", "banon"), ScoreFunctionBuilders.weightFactorFunction(1.3f))).query();
        assertThat(parsedQuery, instanceOf(FunctionScoreQuery.class));
        FunctionScoreQuery functionScoreQuery = (FunctionScoreQuery) parsedQuery;
        assertThat(((TermQuery) functionScoreQuery.getSubQuery()).getTerm(), equalTo(new Term("name.last", "banon")));
        assertThat((double) ((WeightFactorFunction) functionScoreQuery.getFunction()).getWeight(), closeTo(1.3, 0.001));
    }

    @Test
    public void testCustomWeightFactorQueryBuilder_withFunctionScoreWithoutQueryGiven() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(functionScoreQuery(ScoreFunctionBuilders.weightFactorFunction(1.3f))).query();
        assertThat(parsedQuery, instanceOf(FunctionScoreQuery.class));
        FunctionScoreQuery functionScoreQuery = (FunctionScoreQuery) parsedQuery;
        assertThat(functionScoreQuery.getSubQuery() instanceof MatchAllDocsQuery, equalTo(true));
        assertThat((double) ((WeightFactorFunction) functionScoreQuery.getFunction()).getWeight(), closeTo(1.3, 0.001));
    }

    @Test
    public void testSpanTermQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(spanTermQuery("age", 34)).query();
        assertThat(parsedQuery, instanceOf(SpanTermQuery.class));
        SpanTermQuery termQuery = (SpanTermQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(termQuery.getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
    }

    @Test
    public void testSpanTermQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanTerm.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanTermQuery.class));
        SpanTermQuery termQuery = (SpanTermQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(termQuery.getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
    }

    @Test
    public void testSpanNotQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(spanNotQuery(spanTermQuery("age", 34), spanTermQuery("age", 35))).query();
        assertThat(parsedQuery, instanceOf(SpanNotQuery.class));
        SpanNotQuery spanNotQuery = (SpanNotQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(((SpanTermQuery) spanNotQuery.getInclude()).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanNotQuery.getExclude()).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
    }

    @Test
    public void testSpanNotQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanNot.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanNotQuery.class));
        SpanNotQuery spanNotQuery = (SpanNotQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(((SpanTermQuery) spanNotQuery.getInclude()).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanNotQuery.getExclude()).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
    }

    @Test
    public void testSpanWithinQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query expectedQuery = new SpanWithinQuery(new SpanTermQuery(new Term("age", longToPrefixCoded(34, 0))),
                                                  new SpanTermQuery(new Term("age", longToPrefixCoded(35, 0))));
        Query actualQuery = queryParser.parse(spanWithinQuery(spanTermQuery("age", 34), spanTermQuery("age", 35)))
                                              .query();
        assertEquals(expectedQuery, actualQuery);
    }

    @Test
    public void testSpanWithinQueryParser() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query expectedQuery = new SpanWithinQuery(new SpanTermQuery(new Term("age", longToPrefixCoded(34, 0))),
                                                  new SpanTermQuery(new Term("age", longToPrefixCoded(35, 0))));
        String queryText = copyToStringFromClasspath("/org/elasticsearch/index/query/spanWithin.json");
        Query actualQuery = queryParser.parse(queryText).query();
        assertEquals(expectedQuery, actualQuery);
    }

    @Test
    public void testSpanContainingQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query expectedQuery = new SpanContainingQuery(new SpanTermQuery(new Term("age", longToPrefixCoded(34, 0))),
                                                      new SpanTermQuery(new Term("age", longToPrefixCoded(35, 0))));
        Query actualQuery = queryParser.parse(spanContainingQuery(spanTermQuery("age", 34), spanTermQuery("age", 35))).query();
        assertEquals(expectedQuery, actualQuery);
    }

    @Test
    public void testSpanContainingQueryParser() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query expectedQuery = new SpanContainingQuery(new SpanTermQuery(new Term("age", longToPrefixCoded(34, 0))),
                                                      new SpanTermQuery(new Term("age", longToPrefixCoded(35, 0))));
        String queryText = copyToStringFromClasspath("/org/elasticsearch/index/query/spanContaining.json");
        Query actualQuery = queryParser.parse(queryText).query();
        assertEquals(expectedQuery, actualQuery);
    }

    @Test
    public void testSpanFirstQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(spanFirstQuery(spanTermQuery("age", 34), 12)).query();
        assertThat(parsedQuery, instanceOf(SpanFirstQuery.class));
        SpanFirstQuery spanFirstQuery = (SpanFirstQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(((SpanTermQuery) spanFirstQuery.getMatch()).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(spanFirstQuery.getEnd(), equalTo(12));
    }

    @Test
    public void testSpanFirstQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanFirst.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanFirstQuery.class));
        SpanFirstQuery spanFirstQuery = (SpanFirstQuery) parsedQuery;
        // since age is automatically registered in data, we encode it as numeric
        assertThat(((SpanTermQuery) spanFirstQuery.getMatch()).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(spanFirstQuery.getEnd(), equalTo(12));
    }

    @Test
    public void testSpanNearQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(spanNearQuery(12).clause(spanTermQuery("age", 34)).clause(spanTermQuery("age", 35)).clause(spanTermQuery("age", 36)).inOrder(false).collectPayloads(false)).query();
        assertThat(parsedQuery, instanceOf(SpanNearQuery.class));
        SpanNearQuery spanNearQuery = (SpanNearQuery) parsedQuery;
        assertThat(spanNearQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[2]).getTerm(), equalTo(new Term("age", longToPrefixCoded(36, 0))));
        assertThat(spanNearQuery.isInOrder(), equalTo(false));
    }

    @Test
    public void testSpanNearQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanNear.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanNearQuery.class));
        SpanNearQuery spanNearQuery = (SpanNearQuery) parsedQuery;
        assertThat(spanNearQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[2]).getTerm(), equalTo(new Term("age", longToPrefixCoded(36, 0))));
        assertThat(spanNearQuery.isInOrder(), equalTo(false));
    }

    @Test
    public void testFieldMaskingSpanQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanFieldMaskingTerm.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanNearQuery.class));
        SpanNearQuery spanNearQuery = (SpanNearQuery) parsedQuery;
        assertThat(spanNearQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanNearQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) ((FieldMaskingSpanQuery) spanNearQuery.getClauses()[2]).getMaskedQuery()).getTerm(), equalTo(new Term("age_1", "36")));
        assertThat(spanNearQuery.isInOrder(), equalTo(false));
    }


    @Test
    public void testSpanOrQueryBuilder() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(spanOrQuery().clause(spanTermQuery("age", 34)).clause(spanTermQuery("age", 35)).clause(spanTermQuery("age", 36))).query();
        assertThat(parsedQuery, instanceOf(SpanOrQuery.class));
        SpanOrQuery spanOrQuery = (SpanOrQuery) parsedQuery;
        assertThat(spanOrQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[2]).getTerm(), equalTo(new Term("age", longToPrefixCoded(36, 0))));
    }

    @Test
    public void testSpanOrQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanOr.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanOrQuery.class));
        SpanOrQuery spanOrQuery = (SpanOrQuery) parsedQuery;
        assertThat(spanOrQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[2]).getTerm(), equalTo(new Term("age", longToPrefixCoded(36, 0))));
    }

    @Test
    public void testSpanOrQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/spanOr2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanOrQuery.class));
        SpanOrQuery spanOrQuery = (SpanOrQuery) parsedQuery;
        assertThat(spanOrQuery.getClauses().length, equalTo(3));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[0]).getTerm(), equalTo(new Term("age", longToPrefixCoded(34, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[1]).getTerm(), equalTo(new Term("age", longToPrefixCoded(35, 0))));
        assertThat(((SpanTermQuery) spanOrQuery.getClauses()[2]).getTerm(), equalTo(new Term("age", longToPrefixCoded(36, 0))));
    }

    @Test
    public void testSpanMultiTermWildcardQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-wildcard.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        WildcardQuery expectedWrapped = new WildcardQuery(new Term("user", "ki*y"));
        expectedWrapped.setBoost(1.08f);
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper, equalTo(new SpanMultiTermQueryWrapper<MultiTermQuery>(expectedWrapped)));
    }

    @Test
    public void testSpanMultiTermPrefixQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-prefix.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        PrefixQuery expectedWrapped = new PrefixQuery(new Term("user", "ki"));
        expectedWrapped.setBoost(1.08f);
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper, equalTo(new SpanMultiTermQueryWrapper<MultiTermQuery>(expectedWrapped)));
    }

    @Test
    public void testSpanMultiTermFuzzyTermQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-fuzzy-term.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper.getField(), equalTo("user"));
    }

    @Test
    public void testSpanMultiTermFuzzyRangeQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-fuzzy-range.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        NumericRangeQuery<Long> expectedWrapped = NumericRangeQuery.newLongRange("age", NumberFieldMapper.Defaults.PRECISION_STEP_64_BIT, 7l, 17l, true, true);
        expectedWrapped.setBoost(2.0f);
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper, equalTo(new SpanMultiTermQueryWrapper<MultiTermQuery>(expectedWrapped)));
    }

    @Test
    public void testSpanMultiTermNumericRangeQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-range-numeric.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        NumericRangeQuery<Long> expectedWrapped = NumericRangeQuery.newLongRange("age", NumberFieldMapper.Defaults.PRECISION_STEP_64_BIT, 10l, 20l, true, false);
        expectedWrapped.setBoost(2.0f);
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper, equalTo(new SpanMultiTermQueryWrapper<MultiTermQuery>(expectedWrapped)));
    }

    @Test
    public void testSpanMultiTermTermRangeQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/span-multi-term-range-term.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(SpanMultiTermQueryWrapper.class));
        TermRangeQuery expectedWrapped = TermRangeQuery.newStringRange("user", "alice", "bob", true, false);
        expectedWrapped.setBoost(2.0f);
        SpanMultiTermQueryWrapper<MultiTermQuery> wrapper = (SpanMultiTermQueryWrapper<MultiTermQuery>) parsedQuery;
        assertThat(wrapper, equalTo(new SpanMultiTermQueryWrapper<MultiTermQuery>(expectedWrapped)));
    }

    @Test
    public void testQueryQueryBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(filteredQuery(termQuery("name.first", "shay"), termQuery("name.last", "banon"))).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new TermQuery(new Term("name.last", "banon")));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testQueryFilter() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/query-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new ConstantScoreQuery(new TermQuery(new Term("name.last", "banon"))));
        assertEquals(expected, parsedQuery);
    }

    @Test
    public void testFQueryFilter() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/fquery-filter.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        Query expected = Queries.filtered(
                new TermQuery(new Term("name.first", "shay")),
                new ConstantScoreQuery(new TermQuery(new Term("name.last", "banon"))));
        assertEquals(expected, parsedQuery.query());
    }

    @Test
    public void testMoreLikeThisBuilder() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(moreLikeThisQuery("name.first", "name.last").likeText("something").minTermFreq(1).maxQueryTerms(12)).query();
        assertThat(parsedQuery, instanceOf(MoreLikeThisQuery.class));
        MoreLikeThisQuery mltQuery = (MoreLikeThisQuery) parsedQuery;
        assertThat(mltQuery.getMoreLikeFields()[0], equalTo("name.first"));
        assertThat(mltQuery.getLikeText(), equalTo("something"));
        assertThat(mltQuery.getMinTermFrequency(), equalTo(1));
        assertThat(mltQuery.getMaxQueryTerms(), equalTo(12));
    }

    @Test
    public void testMoreLikeThis() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/mlt.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(MoreLikeThisQuery.class));
        MoreLikeThisQuery mltQuery = (MoreLikeThisQuery) parsedQuery;
        assertThat(mltQuery.getMoreLikeFields()[0], equalTo("name.first"));
        assertThat(mltQuery.getMoreLikeFields()[1], equalTo("name.last"));
        assertThat(mltQuery.getLikeText(), equalTo("something"));
        assertThat(mltQuery.getMinTermFrequency(), equalTo(1));
        assertThat(mltQuery.getMaxQueryTerms(), equalTo(12));
    }

    @Test
    public void testMoreLikeThisIds() throws Exception {
        MoreLikeThisQueryParser parser = (MoreLikeThisQueryParser) queryParser.queryParser("more_like_this");
        parser.setFetchService(new MockMoreLikeThisFetchService());

        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/mlt-items.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) parsedQuery;
        assertThat(booleanQuery.getClauses().length, is(1));

        BooleanClause itemClause = booleanQuery.getClauses()[0];
        assertThat(itemClause.getOccur(), is(BooleanClause.Occur.SHOULD));
        assertThat(itemClause.getQuery(), instanceOf(MoreLikeThisQuery.class));
        MoreLikeThisQuery mltQuery = (MoreLikeThisQuery) itemClause.getQuery();

        // check each Fields is for each item
        for (int id = 1; id <= 4; id++) {
            Fields fields = mltQuery.getLikeFields()[id - 1];
            assertThat(termsToString(fields.terms("name.first")), is(String.valueOf(id)));
            assertThat(termsToString(fields.terms("name.last")), is(String.valueOf(id)));
        }
    }

    @Test
    public void testMLTMinimumShouldMatch() throws Exception {
        // setup for mocking fetching items
        MoreLikeThisQueryParser parser = (MoreLikeThisQueryParser) queryParser.queryParser("more_like_this");
        parser.setFetchService(new MockMoreLikeThisFetchService());

        // parsing the ES query
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/mlt-items.json");
        BooleanQuery parsedQuery = (BooleanQuery) queryParser.parse(query).query();

        // get MLT query, other clause is for include/exclude items
        MoreLikeThisQuery mltQuery = (MoreLikeThisQuery) parsedQuery.getClauses()[0].getQuery();

        // all terms must match
        mltQuery.setMinimumShouldMatch("100%");
        mltQuery.setMinWordLen(0);
        mltQuery.setMinDocFreq(0);

        // one document has all values
        MemoryIndex index = new MemoryIndex();
        index.addField("name.first", "apache lucene", new WhitespaceAnalyzer());
        index.addField("name.last", "1 2 3 4", new WhitespaceAnalyzer());

        // two clauses, one for items and one for like_text if set
        BooleanQuery luceneQuery = (BooleanQuery) mltQuery.rewrite(index.createSearcher().getIndexReader());
        BooleanClause[] clauses = luceneQuery.getClauses();

        // check for items
        int minNumberShouldMatch = ((BooleanQuery) (clauses[0].getQuery())).getMinimumNumberShouldMatch();
        assertThat(minNumberShouldMatch, is(4));

        // and for like_text
        minNumberShouldMatch = ((BooleanQuery) (clauses[1].getQuery())).getMinimumNumberShouldMatch();
        assertThat(minNumberShouldMatch, is(2));
    }

    private static class MockMoreLikeThisFetchService extends MoreLikeThisFetchService {

        public MockMoreLikeThisFetchService() {
            super(null, Settings.Builder.EMPTY_SETTINGS);
        }

        @Override
        public MultiTermVectorsResponse fetchResponse(MultiTermVectorsRequest items) throws IOException {
            MultiTermVectorsItemResponse[] responses = new MultiTermVectorsItemResponse[items.size()];
            int i = 0;
            for (TermVectorsRequest item : items) {
                TermVectorsResponse response = new TermVectorsResponse(item.index(), item.type(), item.id());
                response.setExists(true);
                Fields generatedFields = generateFields(item.selectedFields().toArray(Strings.EMPTY_ARRAY), item.id());
                EnumSet<TermVectorsRequest.Flag> flags = EnumSet.of(TermVectorsRequest.Flag.Positions, TermVectorsRequest.Flag.Offsets);
                response.setFields(generatedFields, item.selectedFields(), flags, generatedFields);
                responses[i++] = new MultiTermVectorsItemResponse(response, null);
            }
            return new MultiTermVectorsResponse(responses);
        }
    }

    private static Fields generateFields(String[] fieldNames, String text) throws IOException {
        MemoryIndex index = new MemoryIndex();
        for (String fieldName : fieldNames) {
            index.addField(fieldName, text, new WhitespaceAnalyzer());
        }
        return MultiFields.getFields(index.createSearcher().getIndexReader());
    }

    private static String termsToString(Terms terms) throws IOException {
        String strings = "";
        TermsEnum termsEnum = terms.iterator();
        CharsRefBuilder spare = new CharsRefBuilder();
        BytesRef text;
        while((text = termsEnum.next()) != null) {
            spare.copyUTF8Bytes(text);
            String term = spare.toString();
            strings += term;
        }
        return strings;
    }

    @Test
    public void testGeoDistanceRangeQueryNamed() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        assertThat(parsedQuery.query(), instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery.query();
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery1() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance3.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery4() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance4.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery5() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance5.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery6() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance6.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery7() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance7.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(0.012, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery8() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance8.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.KILOMETERS.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery9() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance9.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery10() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance10.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery11() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance11.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoDistanceRangeQuery12() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_distance12.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoDistanceRangeQuery filter = (GeoDistanceRangeQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.lat(), closeTo(40, 0.00001));
        assertThat(filter.lon(), closeTo(-70, 0.00001));
        assertThat(filter.minInclusiveDistance(), equalTo(Double.NEGATIVE_INFINITY));
        assertThat(filter.maxInclusiveDistance(), closeTo(DistanceUnit.DEFAULT.convert(12, DistanceUnit.MILES), 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilterNamed() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.query(), instanceOf(ConstantScoreQuery.class));
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery.query();
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter1() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox3.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter4() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox4.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter5() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox5.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }

    @Test
    public void testGeoBoundingBoxFilter6() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_boundingbox6.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        InMemoryGeoBoundingBoxQuery filter = (InMemoryGeoBoundingBoxQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.topLeft().lat(), closeTo(40, 0.00001));
        assertThat(filter.topLeft().lon(), closeTo(-70, 0.00001));
        assertThat(filter.bottomRight().lat(), closeTo(30, 0.00001));
        assertThat(filter.bottomRight().lon(), closeTo(-80, 0.00001));
    }


    @Test
    public void testGeoPolygonNamedFilter() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_polygon-named.json");
        ParsedQuery parsedQuery = queryParser.parse(query);
        assertThat(parsedQuery.namedFilters().containsKey("test"), equalTo(true));
        assertThat(parsedQuery.query(), instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery.query();
        GeoPolygonQuery filter = (GeoPolygonQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.points().length, equalTo(4));
        assertThat(filter.points()[0].lat(), closeTo(40, 0.00001));
        assertThat(filter.points()[0].lon(), closeTo(-70, 0.00001));
        assertThat(filter.points()[1].lat(), closeTo(30, 0.00001));
        assertThat(filter.points()[1].lon(), closeTo(-80, 0.00001));
        assertThat(filter.points()[2].lat(), closeTo(20, 0.00001));
        assertThat(filter.points()[2].lon(), closeTo(-90, 0.00001));
    }


    @Test
    public void testGeoPolygonFilterParsingExceptions() throws IOException {
        String[] brokenFiles = new String[]{
                "/org/elasticsearch/index/query/geo_polygon_exception_1.json",
                "/org/elasticsearch/index/query/geo_polygon_exception_2.json",
                "/org/elasticsearch/index/query/geo_polygon_exception_3.json",
                "/org/elasticsearch/index/query/geo_polygon_exception_4.json",
                "/org/elasticsearch/index/query/geo_polygon_exception_5.json"
        };
        for (String brokenFile : brokenFiles) {
            IndexQueryParserService queryParser = queryParser();
            String query = copyToStringFromClasspath(brokenFile);
            try {
                queryParser.parse(query).query();
                fail("parsing a broken geo_polygon filter didn't fail as expected while parsing: " + brokenFile);
            } catch (QueryParsingException e) {
                // success!
            }
        }
    }


    @Test
    public void testGeoPolygonFilter1() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_polygon1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoPolygonQuery filter = (GeoPolygonQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.points().length, equalTo(4));
        assertThat(filter.points()[0].lat(), closeTo(40, 0.00001));
        assertThat(filter.points()[0].lon(), closeTo(-70, 0.00001));
        assertThat(filter.points()[1].lat(), closeTo(30, 0.00001));
        assertThat(filter.points()[1].lon(), closeTo(-80, 0.00001));
        assertThat(filter.points()[2].lat(), closeTo(20, 0.00001));
        assertThat(filter.points()[2].lon(), closeTo(-90, 0.00001));
    }

    @Test
    public void testGeoPolygonFilter2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_polygon2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoPolygonQuery filter = (GeoPolygonQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.points().length, equalTo(4));
        assertThat(filter.points()[0].lat(), closeTo(40, 0.00001));
        assertThat(filter.points()[0].lon(), closeTo(-70, 0.00001));
        assertThat(filter.points()[1].lat(), closeTo(30, 0.00001));
        assertThat(filter.points()[1].lon(), closeTo(-80, 0.00001));
        assertThat(filter.points()[2].lat(), closeTo(20, 0.00001));
        assertThat(filter.points()[2].lon(), closeTo(-90, 0.00001));
    }

    @Test
    public void testGeoPolygonFilter3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_polygon3.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoPolygonQuery filter = (GeoPolygonQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.points().length, equalTo(4));
        assertThat(filter.points()[0].lat(), closeTo(40, 0.00001));
        assertThat(filter.points()[0].lon(), closeTo(-70, 0.00001));
        assertThat(filter.points()[1].lat(), closeTo(30, 0.00001));
        assertThat(filter.points()[1].lon(), closeTo(-80, 0.00001));
        assertThat(filter.points()[2].lat(), closeTo(20, 0.00001));
        assertThat(filter.points()[2].lon(), closeTo(-90, 0.00001));
    }

    @Test
    public void testGeoPolygonFilter4() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geo_polygon4.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) parsedQuery;
        GeoPolygonQuery filter = (GeoPolygonQuery) constantScoreQuery.getQuery();
        assertThat(filter.fieldName(), equalTo("location"));
        assertThat(filter.points().length, equalTo(4));
        assertThat(filter.points()[0].lat(), closeTo(40, 0.00001));
        assertThat(filter.points()[0].lon(), closeTo(-70, 0.00001));
        assertThat(filter.points()[1].lat(), closeTo(30, 0.00001));
        assertThat(filter.points()[1].lon(), closeTo(-80, 0.00001));
        assertThat(filter.points()[2].lat(), closeTo(20, 0.00001));
        assertThat(filter.points()[2].lon(), closeTo(-90, 0.00001));
    }

    @Test
    public void testGeoShapeFilter() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geoShape-filter.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        while (parsedQuery instanceof ConstantScoreQuery) {
            parsedQuery = ((ConstantScoreQuery) parsedQuery).getQuery();
        }
        assertThat(parsedQuery, instanceOf(IntersectsPrefixTreeFilter.class));
    }

    @Test
    public void testGeoShapeQuery() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/geoShape-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        ConstantScoreQuery csq = (ConstantScoreQuery) parsedQuery;
        assertThat(csq.getQuery(), instanceOf(IntersectsPrefixTreeFilter.class));
    }

    @Test
    public void testCommonTermsQuery1() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/commonTerms-query1.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ExtendedCommonTermsQuery.class));
        ExtendedCommonTermsQuery ectQuery = (ExtendedCommonTermsQuery) parsedQuery;
        assertThat(ectQuery.getHighFreqMinimumNumberShouldMatchSpec(), nullValue());
        assertThat(ectQuery.getLowFreqMinimumNumberShouldMatchSpec(), equalTo("2"));
    }

    @Test
    public void testCommonTermsQuery2() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/commonTerms-query2.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ExtendedCommonTermsQuery.class));
        ExtendedCommonTermsQuery ectQuery = (ExtendedCommonTermsQuery) parsedQuery;
        assertThat(ectQuery.getHighFreqMinimumNumberShouldMatchSpec(), equalTo("50%"));
        assertThat(ectQuery.getLowFreqMinimumNumberShouldMatchSpec(), equalTo("5<20%"));
    }

    @Test
    public void testCommonTermsQuery3() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/commonTerms-query3.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ExtendedCommonTermsQuery.class));
        ExtendedCommonTermsQuery ectQuery = (ExtendedCommonTermsQuery) parsedQuery;
        assertThat(ectQuery.getHighFreqMinimumNumberShouldMatchSpec(), nullValue());
        assertThat(ectQuery.getLowFreqMinimumNumberShouldMatchSpec(), equalTo("2"));
    }

    @Test // see #11730
    public void testCommonTermsQuery4() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        Query parsedQuery = queryParser.parse(commonTermsQuery("field", "text").disableCoord(false)).query();
        assertThat(parsedQuery, instanceOf(ExtendedCommonTermsQuery.class));
        ExtendedCommonTermsQuery ectQuery = (ExtendedCommonTermsQuery) parsedQuery;
        assertFalse(ectQuery.isCoordDisabled());
        parsedQuery = queryParser.parse(commonTermsQuery("field", "text").disableCoord(true)).query();
        assertThat(parsedQuery, instanceOf(ExtendedCommonTermsQuery.class));
        ectQuery = (ExtendedCommonTermsQuery) parsedQuery;
        assertTrue(ectQuery.isCoordDisabled());
    }

    @Test(expected = QueryParsingException.class)
    public void assureMalformedThrowsException() throws IOException {
        IndexQueryParserService queryParser;
        queryParser = queryParser();
        String query;
        query = copyToStringFromClasspath("/org/elasticsearch/index/query/faulty-function-score-query.json");
        Query parsedQuery = queryParser.parse(query).query();
    }

    @Test
    public void testFilterParsing() throws IOException {
        IndexQueryParserService queryParser;
        queryParser = queryParser();
        String query;
        query = copyToStringFromClasspath("/org/elasticsearch/index/query/function-filter-score-query.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat((double) (parsedQuery.getBoost()), Matchers.closeTo(3.0, 1.e-7));
    }

    @Test
    public void testBadTypeMatchQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/match-query-bad-type.json");
        QueryParsingException expectedException = null;
        try {
            queryParser.parse(query).query();
        } catch (QueryParsingException qpe) {
            expectedException = qpe;
        }
        assertThat(expectedException, notNullValue());
    }

    @Test
    public void testMultiMatchQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/multiMatch-query-simple.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(DisjunctionMaxQuery.class));
    }

    @Test
    public void testBadTypeMultiMatchQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/multiMatch-query-bad-type.json");
        QueryParsingException expectedException = null;
        try {
            queryParser.parse(query).query();
        } catch (QueryParsingException qpe) {
            expectedException = qpe;
        }
        assertThat(expectedException, notNullValue());
    }

    @Test
    public void testMultiMatchQueryWithFieldsAsString() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/multiMatch-query-fields-as-string.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
    }

    @Test
    public void testSimpleQueryString() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/simple-query-string.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(BooleanQuery.class));
    }

    @Test
    public void testMatchWithFuzzyTranspositions() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/match-with-fuzzy-transpositions.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        assertThat( ((FuzzyQuery) parsedQuery).getTranspositions(), equalTo(true));
    }

    @Test
    public void testMatchWithoutFuzzyTranspositions() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/match-without-fuzzy-transpositions.json");
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(FuzzyQuery.class));
        assertThat( ((FuzzyQuery) parsedQuery).getTranspositions(), equalTo(false));
    }

    // https://github.com/elasticsearch/elasticsearch/issues/7240
    @Test
    public void testEmptyBooleanQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = jsonBuilder().startObject().startObject("bool").endObject().endObject().string();
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(MatchAllDocsQuery.class));
    }

    // https://github.com/elasticsearch/elasticsearch/issues/7240
    @Test
    public void testEmptyBooleanQueryInsideFQuery() throws Exception {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/fquery-with-empty-bool-query.json");
        XContentParser parser = XContentHelper.createParser(new BytesArray(query));
        ParsedQuery parsedQuery = queryParser.parseInnerFilter(parser);
        assertEquals(new ConstantScoreQuery(Queries.filtered(new TermQuery(new Term("text", "apache")), new TermQuery(new Term("text", "apache")))), parsedQuery.query());
    }

    @Test
    public void testProperErrorMessageWhenTwoFunctionsDefinedInQueryBody() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/function-score-query-causing-NPE.json");
        try {
            queryParser.parse(query).query();
            fail("FunctionScoreQueryParser should throw an exception here because two functions in body are not allowed.");
        } catch (QueryParsingException e) {
            assertThat(e.getDetailedMessage(), containsString("use [functions] array if you want to define several functions."));
        }
    }

    @Test
    public void testWeight1fStillProducesWeighFunction() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String queryString = jsonBuilder().startObject()
                .startObject("function_score")
                .startArray("functions")
                .startObject()
                .startObject("field_value_factor")
                .field("field", "popularity")
                .endObject()
                .field("weight", 1.0)
                .endObject()
                .endArray()
                .endObject()
                .endObject().string();
        IndexService indexService = createIndex("testidx", client().admin().indices().prepareCreate("testidx")
                .addMapping("doc",jsonBuilder().startObject()
                        .startObject("properties")
                        .startObject("popularity").field("type", "float").endObject()
                        .endObject()
                        .endObject()));
        SearchContext.setCurrent(createSearchContext(indexService));
        Query query = queryParser.parse(queryString).query();
        assertThat(query, instanceOf(FunctionScoreQuery.class));
        assertThat(((FunctionScoreQuery) query).getFunction(), instanceOf(WeightFactorFunction.class));
        SearchContext.removeCurrent();
    }

    @Test
    public void testProperErrorMessagesForMisplacedWeightsAndFunctions() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        String query = jsonBuilder().startObject().startObject("function_score")
                .startArray("functions")
                .startObject().field("weight", 2).field("boost_factor",2).endObject()
                .endArray()
                .endObject().endObject().string();
        try {
            queryParser.parse(query).query();
            fail("Expect exception here because boost_factor must not have a weight");
        } catch (QueryParsingException e) {
            assertThat(e.getDetailedMessage(), containsString(BoostScoreFunction.BOOST_WEIGHT_ERROR_MESSAGE));
        }
        query = jsonBuilder().startObject().startObject("function_score")
                .startArray("functions")
                .startObject().field("boost_factor",2).endObject()
                .endArray()
                .field("weight", 2)
                .endObject().endObject().string();
        try {
            queryParser.parse(query).query();
            fail("Expect exception here because array of functions and one weight in body is not allowed.");
        } catch (QueryParsingException e) {
            assertThat(e.getDetailedMessage(), containsString("you can either define [functions] array or a single function, not both. already found [functions] array, now encountering [weight]."));
        }
        query = jsonBuilder().startObject().startObject("function_score")
                .field("weight", 2)
                .startArray("functions")
                .startObject().field("boost_factor",2).endObject()
                .endArray()
                .endObject().endObject().string();
        try {
            queryParser.parse(query).query();
            fail("Expect exception here because array of functions and one weight in body is not allowed.");
        } catch (QueryParsingException e) {
            assertThat(e.getDetailedMessage(), containsString("you can either define [functions] array or a single function, not both. already found [weight], now encountering [functions]."));
        }
    }

    // https://github.com/elasticsearch/elasticsearch/issues/6722
    public void testEmptyBoolSubClausesIsMatchAll() throws IOException {
        String query = copyToStringFromClasspath("/org/elasticsearch/index/query/bool-query-with-empty-clauses-for-parsing.json");
        IndexService indexService = createIndex("testidx", client().admin().indices().prepareCreate("testidx")
                .addMapping("foo", "nested", "type=nested"));
        SearchContext.setCurrent(createSearchContext(indexService));
        IndexQueryParserService queryParser = indexService.queryParserService();
        Query parsedQuery = queryParser.parse(query).query();
        assertThat(parsedQuery, instanceOf(ConstantScoreQuery.class));
        assertThat(((ConstantScoreQuery) parsedQuery).getQuery(), instanceOf(ToParentBlockJoinQuery.class));
        assertThat(((ConstantScoreQuery) parsedQuery).getQuery().toString(), equalTo("ToParentBlockJoinQuery (+*:* #random_access(QueryWrapperFilter(_type:__nested)))"));
        SearchContext.removeCurrent();
    }

    /**
     * helper to extract term from TermQuery. */
    private Term getTerm(Query query) {
        while (query instanceof QueryWrapperFilter) {
            query = ((QueryWrapperFilter) query).getQuery();
        }
        TermQuery wrapped = (TermQuery) query;
        return wrapped.getTerm();
    }

    public void testDefaultBooleanQueryMinShouldMatch() throws Exception {
        IndexQueryParserService queryParser = queryParser();

        // Queries have a minShouldMatch of 0
        BooleanQuery bq = (BooleanQuery) queryParser.parse(boolQuery().must(termQuery("foo", "bar"))).query();
        assertEquals(0, bq.getMinimumNumberShouldMatch());

        bq = (BooleanQuery) queryParser.parse(boolQuery().should(termQuery("foo", "bar"))).query();
        assertEquals(0, bq.getMinimumNumberShouldMatch());

        // Filters have a minShouldMatch of 0/1
        ConstantScoreQuery csq = (ConstantScoreQuery) queryParser.parse(constantScoreQuery(boolQuery().must(termQuery("foo", "bar")))).query();
        bq = (BooleanQuery) csq.getQuery();
        assertEquals(0, bq.getMinimumNumberShouldMatch());

        csq = (ConstantScoreQuery) queryParser.parse(constantScoreQuery(boolQuery().should(termQuery("foo", "bar")))).query();
        bq = (BooleanQuery) csq.getQuery();
        assertEquals(1, bq.getMinimumNumberShouldMatch());
    }

    public void testTermsQueryFilter() throws Exception {
        // TermsQuery is tricky in that it parses differently as a query or a filter
        IndexQueryParserService queryParser = queryParser();
        Query q = queryParser.parse(termsQuery("foo", "bar")).query();
        assertThat(q, instanceOf(BooleanQuery.class));

        ConstantScoreQuery csq = (ConstantScoreQuery) queryParser.parse(constantScoreQuery(termsQuery("foo", "bar"))).query();
        q = csq.getQuery();
        assertThat(q, instanceOf(TermsQuery.class));
    }

    @Test
    public void testBlendedRewriteMethod() throws IOException {
        IndexQueryParserService queryParser = queryParser();
        for (String rewrite : Arrays.asList("top_terms_blended_freqs_10", "topTermsBlendedFreqs10")) {
            Query parsedQuery = queryParser.parse(prefixQuery("field", "val").rewrite(rewrite)).query();
            assertThat(parsedQuery, instanceOf(PrefixQuery.class));
            PrefixQuery prefixQuery = (PrefixQuery) parsedQuery;
            assertThat(prefixQuery.getPrefix(), equalTo(new Term("field", "val")));
            assertThat(prefixQuery.getRewriteMethod(), instanceOf(MultiTermQuery.TopTermsBlendedFreqScoringRewrite.class));
        }
    }
}
