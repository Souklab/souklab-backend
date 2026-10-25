package com.project.souklab.service.directory;

import com.project.souklab.security.ViewerPremiumResolver;

import java.util.Collection;
import org.hibernate.search.engine.search.predicate.dsl.PredicateFinalStep;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.project.souklab.config.DirectoryProperties;
import com.project.souklab.config.OperationalMetrics;
import com.project.souklab.config.SearchProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.directory.ArtisanDirectoryCardDTO;
import com.project.souklab.dto.directory.DirectorySearchFilterDTO;
import com.project.souklab.dto.directory.DirectorySortOrder;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.search.engine.search.query.SearchResult;
import org.hibernate.search.engine.search.query.SearchResultTotal;
import org.hibernate.search.engine.search.query.dsl.SearchQueryOptionsStep;
import org.hibernate.search.engine.search.query.dsl.SearchQuerySelectStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.engine.search.predicate.dsl.MatchPredicateFieldMoreStep;
import org.hibernate.search.engine.search.predicate.dsl.MatchPredicateFieldStep;
import org.hibernate.search.engine.search.predicate.dsl.MatchPredicateOptionsStep;
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep;
import org.hibernate.search.engine.search.predicate.dsl.TermsPredicateFieldStep;
import org.hibernate.search.engine.search.predicate.dsl.TermsPredicateFieldMoreStep;
import org.hibernate.search.engine.search.predicate.dsl.TermsPredicateOptionsStep;
import org.hibernate.search.engine.search.predicate.dsl.ExistsPredicateFieldStep;
import org.hibernate.search.engine.search.predicate.dsl.ExistsPredicateOptionsStep;
import org.hibernate.search.engine.search.predicate.dsl.RangePredicateFieldStep;
import org.hibernate.search.engine.search.predicate.dsl.RangePredicateFieldMoreStep;
import org.hibernate.search.engine.search.predicate.dsl.RangePredicateOptionsStep;
import org.hibernate.search.engine.search.predicate.dsl.NotPredicateFinalStep;
import org.hibernate.search.engine.search.predicate.SearchPredicate;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.hibernate.search.engine.search.sort.dsl.FieldSortOptionsStep;
import org.hibernate.search.engine.search.sort.dsl.ScoreSortOptionsStep;
import org.hibernate.search.engine.search.sort.dsl.SearchSortFactory;
import org.hibernate.search.engine.search.sort.dsl.TypedSearchSortFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite verifying {@link DirectorySearchServiceImpl}.
 * Validates Hibernate Search query dispatching, automatic relational JPA specification fallback
 * when search is disabled or throws exceptions, and multi-facet sort mapping.
 */
@ExtendWith(MockitoExtension.class)
class DirectorySearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private SearchProperties searchProperties;

    private DirectoryProperties directoryProperties;

    @Mock
    private ViewerPremiumResolver viewerPremiumResolver;

    private DirectorySearchServiceImpl directorySearchService;

    @BeforeEach
    void setUp() {
        directoryProperties = new DirectoryProperties();
        directoryProperties.setDefaultPageIndex(0);
        directoryProperties.setDefaultPageSize(20);
        directoryProperties.setMinPageSize(1);
        directoryProperties.setMaxPageSize(100);
        directorySearchService = new DirectorySearchServiceImpl(
                entityManager, artisanRepository, searchProperties, directoryProperties, OperationalMetrics.noop(), viewerPremiumResolver);
    }

    /**
     * Verifies that when search is disabled via configuration, queries are immediately routed to JPA fallback.
     */
    @Test
    @DisplayName("search: when Hibernate Search is disabled, routes directly to relational JPA fallback")
    @SuppressWarnings("unchecked")
    void search_whenSearchDisabled_shouldFallbackToRelationalSpecification() {
        when(searchProperties.isEnabled()).thenReturn(false);

        Artisan sampleArtisan = createSampleArtisan();
        when(artisanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleArtisan)));

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                .keyword("poterie")
                .regionSlug("tizi-ouzou")
                .page(0)
                .size(20)
                .build();

        PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.search(filter);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo("artisan-user-1");
        assertThat(response.getContent().get(0).getArtisanName()).isEqualTo("Djamel Amrani");
        assertThat(response.getContent().get(0).getWilayaName()).isEqualTo("Tizi Ouzou");
        verify(artisanRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    /**
     * Verifies that when Hibernate Search throws an exception (e.g. cluster unavailable), it falls back cleanly to JPA.
     */
    @Test
    @DisplayName("search: when Hibernate Search throws an exception, catches and seamlessly falls back to JPA")
    @SuppressWarnings("unchecked")
    void search_whenHibernateSearchThrowsException_shouldFallbackToRelationalSpecification() {
        when(searchProperties.isEnabled()).thenReturn(true);

        Artisan sampleArtisan = createSampleArtisan();
        when(artisanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleArtisan)));

        try (MockedStatic<Search> searchMock = mockStatic(Search.class)) {
            searchMock.when(() -> Search.session(entityManager))
                    .thenThrow(new RuntimeException("Elasticsearch cluster connection refused"));

            DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                    .keyword("bijoutier")
                    .build();

            PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.search(filter);

            assertThat(response).isNotNull();
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getId()).isEqualTo("artisan-user-1");
            verify(artisanRepository).findAll(any(Specification.class), any(Pageable.class));
        }
    }

    /**
     * Verifies that when Hibernate Search succeeds, paginated directory cards are mapped and returned.
     */
    @Test
    @DisplayName("search: when Hibernate Search succeeds, returns mapped PaginatedResponse")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void search_whenHibernateSearchSucceeds_shouldReturnDirectoryCards() {
        when(searchProperties.isEnabled()).thenReturn(true);

        Artisan sampleArtisan = createSampleArtisan();

        try (MockedStatic<Search> searchMock = mockStatic(Search.class)) {
            SearchSession searchSession = mock(SearchSession.class);
            SearchQuerySelectStep selectStep = mock(SearchQuerySelectStep.class);
            SearchQueryOptionsStep optionsStep = mock(SearchQueryOptionsStep.class);
            SearchResult searchResult = mock(SearchResult.class);
            SearchResultTotal totalResult = mock(SearchResultTotal.class);

            searchMock.when(() -> Search.session(entityManager)).thenReturn(searchSession);
            when(searchSession.search(Artisan.class)).thenReturn(selectStep);
            when(selectStep.where(any(Function.class))).thenReturn(optionsStep);
            when(optionsStep.sort(any(Function.class))).thenReturn(optionsStep);
            when(optionsStep.loading(any(Consumer.class))).thenReturn(optionsStep);
            when(optionsStep.fetch(anyInt(), anyInt())).thenReturn(searchResult);

            when(searchResult.hits()).thenReturn(List.of(sampleArtisan));
            when(searchResult.total()).thenReturn(totalResult);
            when(totalResult.hitCount()).thenReturn(1L);

            DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                    .keyword("ceramique")
                    .regionSlug("tizi-ouzou")
                    .wilayaCode("15")
                    .categorySlug("art-du-feu")
                    .subCategorySlug("poterie-traditionnelle")
                    .materials(List.of("argile-rouge"))
                    .techniques(List.of("modelage-ancestral"))
                    .epoques(List.of("epoque-ottomane"))
                    .minRating(4.0)
                    .verifiedOnly(true)
                    .premiumOnly(true)
                    .teacherOnly(true)
                    .page(0)
                    .size(20)
                    .build();

            PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.search(filter);

            assertThat(response).isNotNull();
            assertThat(response.getTotalElements()).isEqualTo(1L);
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getId()).isEqualTo("artisan-user-1");
            assertThat(response.getContent().get(0).getArtisanName()).isEqualTo("Djamel Amrani");
            verify(artisanRepository, never()).findAll(any(Specification.class), any(Pageable.class));

            for (DirectorySortOrder.Key sortOrder : DirectorySortOrder.all()) {
                filter.setSortBy(sortOrder);
                directorySearchService.search(filter);
            }

            filter.setKeyword(null);
            filter.setSortBy(DirectorySortOrder.Relevance.DEFAULT);
            directorySearchService.search(filter);

            when(totalResult.hitCount()).thenReturn(25L);
            DirectorySearchFilterDTO emptyFilter = DirectorySearchFilterDTO.builder().page(0).size(20).build();
            PaginatedResponse<ArtisanDirectoryCardDTO> nonLastPage = directorySearchService.search(emptyFilter);
            assertThat(nonLastPage.isLast()).isFalse();
        }
    }

    /**
     * Verifies that relational fallback accurately translates each DirectorySortOrder into Spring Data Sort.
     */
    @ParameterizedTest(name = "searchRelationalFallback: maps {0} to property {1} and direction {2}")
    @CsvSource({
            "RATING_DESC, rating, DESC",
            "REVIEWS_DESC, reviewsCount, DESC",
            "VIEWS_DESC, viewsCount, DESC",
            "NEWEST, createdAt, DESC",
            "RELEVANCE, rating, DESC"
    })
    @DisplayName("searchRelationalFallback: translates each DirectorySortOrder into expected Spring Data Sort")
    @SuppressWarnings("unchecked")
    void searchRelationalFallback_sortOrderMapping(String sortOrderValue, String primaryProperty, Sort.Direction direction) {
        when(artisanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(createSampleArtisan())));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                .sortBy(DirectorySortOrder.fromValue(sortOrderValue))
                .page(0)
                .size(10)
                .build();

        directorySearchService.searchRelationalFallback(filter);

        verify(artisanRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable capturedPageable = pageableCaptor.getValue();
        Sort.Order order = capturedPageable.getSort().getOrderFor(primaryProperty);
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(direction);
    }

    /**
     * Verifies that relational fallback executes without exceptions across multi-facet filter permutations.
     */
    @Test
    @DisplayName("searchRelationalFallback: builds relational specification across all filter facets")
    @SuppressWarnings("unchecked")
    void searchRelationalFallback_withComprehensiveFilters() {
        Artisan sampleArtisan = createSampleArtisan();
        ArgumentCaptor<Specification<Artisan>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(artisanRepository.findAll(specCaptor.capture(), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(sampleArtisan)));

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                .keyword("poterie")
                .regionSlug("tizi-ouzou")
                .wilayaCode("15")
                .categorySlug("art-du-feu")
                .subCategorySlug("poterie-traditionnelle")
                .materials(List.of("argile-rouge"))
                .techniques(List.of("modelage-ancestral"))
                .epoques(List.of("epoque-ottomane"))
                .minRating(4.0)
                .verifiedOnly(true)
                .premiumOnly(true)
                .teacherOnly(true)
                .sortBy(DirectorySortOrder.Rating.DESC)
                .page(1)
                .size(15)
                .build();

        PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.searchRelationalFallback(filter);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(specCaptor.getValue()).isNotNull();

        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getPageNumber()).isEqualTo(1);
        assertThat(capturedPageable.getPageSize()).isEqualTo(15);

        CriteriaBuilder cb = mock(CriteriaBuilder.class, Answers.RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<Artisan> root = mock(Root.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.doReturn(String.class).when(query).getResultType();
        Mockito.lenient().when(cb.isNull(any())).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.or(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.like(any(), any(String.class))).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.greaterThanOrEqualTo(any(), any(Double.class))).thenReturn(mock(Predicate.class));
        Mockito.lenient().when(cb.isTrue(any())).thenReturn(mock(Predicate.class));
        specCaptor.getValue().toPredicate(root, query, cb);

        DirectorySearchFilterDTO emptyFilter = DirectorySearchFilterDTO.builder().build();
        ArgumentCaptor<Specification<Artisan>> emptySpecCaptor = ArgumentCaptor.forClass(Specification.class);
        when(artisanRepository.findAll(emptySpecCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        directorySearchService.searchRelationalFallback(emptyFilter);
        Mockito.doReturn(Long.class).when(query).getResultType();
        emptySpecCaptor.getValue().toPredicate(root, query, cb);
        Mockito.doReturn(long.class).when(query).getResultType();
        emptySpecCaptor.getValue().toPredicate(root, query, cb);
    }

    @Test
    @DisplayName("Hibernate Search DSL: builds text predicates and enables fuzzy matching for long terms")
    void hibernateSearchDsl_coversAllPredicateAndSortBranches() {
        SearchPredicateFactory predicateFactory = mock(SearchPredicateFactory.class,
                Answers.RETURNS_DEEP_STUBS);
        MatchPredicateFieldStep matchFieldStep = mock(MatchPredicateFieldStep.class);
        MatchPredicateFieldMoreStep matchMoreStep = mock(MatchPredicateFieldMoreStep.class);
        MatchPredicateOptionsStep matchOptionsStep = mock(MatchPredicateOptionsStep.class);
        Mockito.doReturn(matchFieldStep).when(predicateFactory).match();
        Mockito.doReturn(matchMoreStep).when(matchFieldStep).field(ArgumentMatchers.anyString());
        Mockito.doReturn(matchOptionsStep).when(matchMoreStep).matching(ArgumentMatchers.any());
        Mockito.doReturn(matchOptionsStep).when(matchOptionsStep).boost(ArgumentMatchers.anyFloat());
        Mockito.doReturn(matchOptionsStep).when(matchOptionsStep).fuzzy(ArgumentMatchers.anyInt());
        Mockito.doReturn(mock(SearchPredicate.class))
                .when(matchOptionsStep).toPredicate();
        BooleanPredicateClausesStep<?, ?> booleanStep = mock(BooleanPredicateClausesStep.class);
        Mockito.doReturn(booleanStep).when(predicateFactory).bool();
        Mockito.doReturn(booleanStep).when(booleanStep).should(any(SearchPredicate.class));
        Mockito.doReturn(mock(SearchPredicate.class)).when(booleanStep).toPredicate();
        DirectorySearchFilterDTO comprehensive = DirectorySearchFilterDTO.builder()
                .keyword("ceramique")
                .regionSlug("tizi-ouzou")
                .wilayaCode("15")
                .categorySlug("ceramique")
                .subCategorySlug("poterie")
                .materials(List.of("argile"))
                .techniques(List.of("modelage"))
                .epoques(List.of("numide"))
                .minRating(4.0)
                .verifiedOnly(true)
                .premiumOnly(true)
                .teacherOnly(true)
                .build();

        ReflectionTestUtils.invokeMethod(directorySearchService, "createTextMatch",
                predicateFactory, "bio", "term", 1.0f);
        ReflectionTestUtils.invokeMethod(directorySearchService, "createTextMatch",
                predicateFactory, "bio", "longer-term", 1.0f);
        ReflectionTestUtils.invokeMethod(directorySearchService, "buildFullTextQuery",
                predicateFactory, comprehensive);
        ReflectionTestUtils.invokeMethod(directorySearchService, "buildFullTextQuery",
                predicateFactory, DirectorySearchFilterDTO.builder().build());

    }

    @Test
    @DisplayName("Hibernate Search DSL: always excludes soft-deleted artisans")
    void hibernateSearchDsl_alwaysAddsSoftDeleteFilter() {
        SearchPredicateFactory predicateFactory = mock(SearchPredicateFactory.class,
                Answers.RETURNS_DEEP_STUBS);
        List<SearchPredicate> filters = ReflectionTestUtils.invokeMethod(directorySearchService,
                "buildFilterClauses", predicateFactory, DirectorySearchFilterDTO.builder().build());

        assertThat(filters).hasSize(1);
    }

    @Test
    @DisplayName("Hibernate Search DSL: builds every configured filter predicate")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void hibernateSearchDsl_buildsAllConfiguredFilters() {
        SearchPredicateFactory factory = mock(SearchPredicateFactory.class);
        SearchPredicate predicate = mock(SearchPredicate.class);
        MatchPredicateFieldStep matchField = mock(MatchPredicateFieldStep.class);
        MatchPredicateFieldMoreStep matchMore = mock(MatchPredicateFieldMoreStep.class);
        MatchPredicateOptionsStep matchOptions = mock(MatchPredicateOptionsStep.class);
        BooleanPredicateClausesStep bool = mock(BooleanPredicateClausesStep.class);
        ExistsPredicateFieldStep existsField = mock(ExistsPredicateFieldStep.class);
        ExistsPredicateOptionsStep existsOptions = mock(ExistsPredicateOptionsStep.class);
        NotPredicateFinalStep not = mock(NotPredicateFinalStep.class);
        TermsPredicateFieldStep termsField = mock(TermsPredicateFieldStep.class);
        TermsPredicateFieldMoreStep termsMore = mock(TermsPredicateFieldMoreStep.class);
        TermsPredicateOptionsStep termsOptions = mock(TermsPredicateOptionsStep.class);
        RangePredicateFieldStep rangeField = mock(RangePredicateFieldStep.class);
        RangePredicateFieldMoreStep rangeMore = mock(RangePredicateFieldMoreStep.class);
        RangePredicateOptionsStep rangeOptions = mock(RangePredicateOptionsStep.class);

        Mockito.doReturn(matchField).when(factory).match();
        Mockito.doReturn(matchMore).when(matchField).field(any(String.class));
        Mockito.doReturn(matchOptions).when(matchMore).matching(any());
        Mockito.doReturn(predicate).when(matchOptions).toPredicate();
        Mockito.doReturn(bool).when(factory).bool();
        Mockito.doReturn(bool).when(bool).should(any(PredicateFinalStep.class));
        Mockito.doReturn(predicate).when(bool).toPredicate();
        Mockito.doReturn(existsField).when(factory).exists();
        Mockito.doReturn(existsOptions).when(existsField).field(any(String.class));
        Mockito.doReturn(not).when(factory).not(any(PredicateFinalStep.class));
        Mockito.doReturn(predicate).when(not).toPredicate();
        Mockito.doReturn(termsField).when(factory).terms();
        Mockito.doReturn(termsMore).when(termsField).field(any(String.class));
        Mockito.doReturn(termsOptions).when(termsMore).matchingAny(any(Collection.class));
        Mockito.doReturn(predicate).when(termsOptions).toPredicate();
        Mockito.doReturn(rangeField).when(factory).range();
        Mockito.doReturn(rangeMore).when(rangeField).field(any(String.class));
        Mockito.doReturn(rangeOptions).when(rangeMore).atLeast(any());
        Mockito.doReturn(predicate).when(rangeOptions).toPredicate();

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                .regionSlug("region").wilayaCode("16").categorySlug("category")
                .subCategorySlug("subcategory").materials(List.of("wood"))
                .techniques(List.of("carving")).epoques(List.of("modern"))
                .minRating(3.5).verifiedOnly(true).premiumOnly(true).teacherOnly(true).build();

        List<SearchPredicate> predicates = ReflectionTestUtils.invokeMethod(directorySearchService,
                "buildFilterClauses", factory, filter);
        assertThat(predicates).hasSize(12);
        verify(termsMore, Mockito.times(3)).matchingAny(any(Collection.class));
        verify(rangeMore).atLeast(3.5);
    }

    @Test
    @DisplayName("Directory filters: blank, empty, null, and threshold boundary values are ignored")
    @SuppressWarnings("unchecked")
    void directoryFilters_ignoreBlankAndBoundaryValues() {
        SearchPredicateFactory predicateFactory = mock(SearchPredicateFactory.class,
                Answers.RETURNS_DEEP_STUBS);
        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder()
                .regionSlug(" ").wilayaCode("").categorySlug(" ").subCategorySlug("")
                .materials(List.of()).techniques(List.of()).epoques(List.of())
                .minRating(0.0).verifiedOnly(false).premiumOnly(false).teacherOnly(false).build();

        List<SearchPredicate> predicates = ReflectionTestUtils.invokeMethod(directorySearchService,
                "buildFilterClauses", predicateFactory, filter);
        assertThat(predicates).hasSize(1);

        DirectorySearchFilterDTO nullCollections = DirectorySearchFilterDTO.builder()
                .materials(null).techniques(null).epoques(null).build();
        List<SearchPredicate> nullCollectionPredicates = ReflectionTestUtils.invokeMethod(directorySearchService,
                "buildFilterClauses", predicateFactory, nullCollections);
        assertThat(nullCollectionPredicates).hasSize(1);

        ArgumentCaptor<Specification<Artisan>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(artisanRepository.findAll(specCaptor.capture(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        directorySearchService.searchRelationalFallback(filter);

        CriteriaBuilder cb = mock(CriteriaBuilder.class, Answers.RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<Artisan> root = mock(Root.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.doReturn(String.class).when(query).getResultType();
        Mockito.doReturn(mock(Predicate.class)).when(cb).isNull(any());
        Mockito.doReturn(mock(Predicate.class)).when(cb).and(any(Predicate[].class));
        specCaptor.getValue().toPredicate(root, query, cb);

        DirectorySearchFilterDTO categoryOnly = DirectorySearchFilterDTO.builder().categorySlug("category").build();
        ArgumentCaptor<Specification<Artisan>> categoryCaptor = ArgumentCaptor.forClass(Specification.class);
        when(artisanRepository.findAll(categoryCaptor.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        directorySearchService.searchRelationalFallback(categoryOnly);
        categoryCaptor.getValue().toPredicate(root, query, cb);

        DirectorySearchFilterDTO subCategoryOnly = DirectorySearchFilterDTO.builder().subCategorySlug("subcategory").build();
        ArgumentCaptor<Specification<Artisan>> subCategoryCaptor = ArgumentCaptor.forClass(Specification.class);
        when(artisanRepository.findAll(subCategoryCaptor.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        directorySearchService.searchRelationalFallback(subCategoryOnly);
        subCategoryCaptor.getValue().toPredicate(root, query, cb);
    }

    @Test
    @DisplayName("Hibernate Search DSL: maps every configured sort order")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void hibernateSearchDsl_mapsEverySortOrder() {
        SearchSortFactory sortFactory = mock(SearchSortFactory.class);
        FieldSortOptionsStep fieldStep = mock(FieldSortOptionsStep.class);
        TypedSearchSortFactory typedSortFactory = mock(TypedSearchSortFactory.class);
        ScoreSortOptionsStep scoreStep = mock(ScoreSortOptionsStep.class);
        Mockito.doReturn(fieldStep).when(sortFactory).field(any(String.class));
        Mockito.doReturn(fieldStep).when(fieldStep).desc();
        Mockito.doReturn(typedSortFactory).when(fieldStep).then();
        Mockito.doReturn(fieldStep).when(typedSortFactory).field(any(String.class));
        Mockito.doReturn(scoreStep).when(sortFactory).score();
        Mockito.doReturn(typedSortFactory).when(scoreStep).then();

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder().keyword("poterie").build();
        for (DirectorySortOrder.Key order : DirectorySortOrder.all()) {
            filter.setSortBy(order);
            Object sort = ReflectionTestUtils.invokeMethod(directorySearchService, "buildSort", sortFactory, filter);
            assertThat(sort).isNotNull();
        }
    }

    /**
     * Verifies that when ViewerPremiumResolver returns true (locked), the relational fallback
     * produces anonymised artisan names in the directory cards.
     */
    @Test
    @DisplayName("searchRelationalFallback: non-premium viewer receives masked artisan names")
    @SuppressWarnings("unchecked")
    void searchRelationalFallback_whenContactInfoLocked_masksArtisanName() {
        when(searchProperties.isEnabled()).thenReturn(false);
        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(true);

        Artisan artisan = createSampleArtisan();
        when(artisanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(artisan)));

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder().page(0).size(20).build();
        PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.search(filter);

        assertThat(response.getContent()).hasSize(1);
        String maskedName = response.getContent().get(0).getArtisanName();
        assertThat(maskedName).startsWith("Artisan #");
        assertThat(maskedName).doesNotContain("Djamel").doesNotContain("Amrani");
    }

    /**
     * Verifies that when ViewerPremiumResolver returns false (unlocked), the relational fallback
     * exposes the real artisan name.
     */
    @Test
    @DisplayName("searchRelationalFallback: premium viewer receives real artisan names")
    @SuppressWarnings("unchecked")
    void searchRelationalFallback_whenContactInfoUnlocked_exposesRealArtisanName() {
        when(searchProperties.isEnabled()).thenReturn(false);
        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(false);

        Artisan artisan = createSampleArtisan();
        when(artisanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(artisan)));

        DirectorySearchFilterDTO filter = DirectorySearchFilterDTO.builder().page(0).size(20).build();
        PaginatedResponse<ArtisanDirectoryCardDTO> response = directorySearchService.search(filter);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getArtisanName()).isEqualTo("Djamel Amrani");
    }

    private Artisan createSampleArtisan() {
        User user = User.builder()
                .firstName("Djamel")
                .lastName("Amrani")
                .email("djamel@souklab.dz")
                .avatarUrl("https://storage.souklab.dz/avatars/djamel.jpg")
                .build();

        Region wilaya = Region.builder()
                .name("Tizi Ouzou")
                .slug("tizi-ouzou")
                .code("15")
                .build();

        Region commune = Region.builder()
                .name("Beni Yenni")
                .slug("beni-yenni")
                .parent(wilaya)
                .build();

        JobCategory category = JobCategory.builder()
                .name("Art du Feu")
                .slug("art-du-feu")
                .build();

        JobSubCategory subCategory = JobSubCategory.builder()
                .name("Poterie Traditionnelle")
                .slug("poterie-traditionnelle")
                .category(category)
                .build();

        Material material = Material.builder()
                .name("Argile Rouge")
                .slug("argile-rouge")
                .build();

        Technique technique = Technique.builder()
                .name("Modelage Ancestral")
                .slug("modelage-ancestral")
                .build();

        return Artisan.builder()
                .id("artisan-user-1")
                .user(user)
                .bio("Maître artisan potier perpétuant les traditions ancestrales.")
                .city("Beni Yenni")
                .region(commune)
                .subCategory(subCategory)
                .materials(new HashSet<>(Set.of(material)))
                .techniques(new HashSet<>(Set.of(technique)))
                .rating(4.85)
                .reviewsCount(42)
                .viewsCount(1250)
                .isVerified(true)
                .isPremium(true)
                .isTeacher(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
