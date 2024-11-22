/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.query.results.internal.dynamic;

import org.hibernate.AssertionFailure;
import org.hibernate.LockMode;
import org.hibernate.engine.FetchTiming;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.CollectionPart;
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.results.FetchBuilder;
import org.hibernate.query.results.LegacyFetchBuilder;
import org.hibernate.query.results.internal.DomainResultCreationStateImpl;
import org.hibernate.query.results.internal.ResultsHelper;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.spi.SqlAliasBase;
import org.hibernate.sql.ast.spi.SqlAliasBaseConstant;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.from.TableGroupJoinProducer;
import org.hibernate.sql.ast.tree.from.TableReference;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMetadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static org.hibernate.query.results.internal.ResultsHelper.impl;

/**
 * @author Steve Ebersole
 * @author Christian Beikov
 */
public class DynamicFetchBuilderLegacy
		implements LegacyFetchBuilder, DynamicFetchBuilder,
				NativeQuery.FetchReturn, NativeQuery.ReturnableResultNode, DynamicFetchBuilderContainer {

	private static final String ELEMENT_PREFIX = CollectionPart.Nature.ELEMENT.getName() + ".";
	private static final String INDEX_PREFIX = CollectionPart.Nature.INDEX.getName() + ".";

	private final String tableAlias;

	private final String ownerTableAlias;
	private final String fetchableName;

	private final List<String> columnNames;
	private final Map<String, FetchBuilder> fetchBuilderMap;
	private final DynamicResultBuilderEntityStandard resultBuilderEntity;

	private LockMode lockMode;

	public DynamicFetchBuilderLegacy(
			String tableAlias,
			String ownerTableAlias,
			String fetchableName,
			List<String> columnNames,
			Map<String, FetchBuilder> fetchBuilderMap) {
		this( tableAlias, ownerTableAlias, fetchableName, columnNames, fetchBuilderMap, null );
	}

	public DynamicFetchBuilderLegacy(
			String tableAlias,
			String ownerTableAlias,
			String fetchableName,
			List<String> columnNames,
			Map<String, FetchBuilder> fetchBuilderMap,
			DynamicResultBuilderEntityStandard resultBuilderEntity) {
		this.tableAlias = tableAlias;
		this.ownerTableAlias = ownerTableAlias;
		this.fetchableName = fetchableName;
		this.columnNames = columnNames;
		this.fetchBuilderMap = fetchBuilderMap;
		this.resultBuilderEntity = resultBuilderEntity;
	}

	@Override
	public String getTableAlias() {
		return tableAlias;
	}

	@Override
	public String getOwnerAlias() {
		return ownerTableAlias;
	}

	@Override
	public String getFetchableName() {
		return fetchableName;
	}

	@Override
	public DynamicFetchBuilderLegacy cacheKeyInstance() {
		return new DynamicFetchBuilderLegacy(
				tableAlias,
				ownerTableAlias,
				fetchableName,
				columnNames == null ? null : List.copyOf( columnNames ),
				fetchBuilderMap(),
				resultBuilderEntity == null ? null : resultBuilderEntity.cacheKeyInstance()
		);
	}

	private Map<String, FetchBuilder> fetchBuilderMap() {
		if ( this.fetchBuilderMap == null ) {
			return null;
		}
		else {
			final Map<String, FetchBuilder> fetchBuilderMap = new HashMap<>( this.fetchBuilderMap.size() );
			for ( Map.Entry<String, FetchBuilder> entry : this.fetchBuilderMap.entrySet() ) {
				fetchBuilderMap.put( entry.getKey(), entry.getValue().cacheKeyInstance() );
			}
			return fetchBuilderMap;
		}
	}

	@Override
	public Fetch buildFetch(
			FetchParent parent,
			NavigablePath fetchPath,
			JdbcValuesMetadata jdbcResultsMetadata,
			DomainResultCreationState domainResultCreationState) {
		final DomainResultCreationStateImpl creationState = impl( domainResultCreationState );
		final TableGroup ownerTableGroup = creationState.getFromClauseAccess().findByAlias( ownerTableAlias );
		final AttributeMapping attributeMapping =
				parent.getReferencedMappingContainer().findContainingEntityMapping()
						.findDeclaredAttributeMapping( fetchableName );
		final TableGroup tableGroup = tableGroup( fetchPath, attributeMapping, ownerTableGroup, creationState );

		if ( columnNames != null ) {
			final ForeignKeyDescriptor keyDescriptor = getForeignKeyDescriptor( attributeMapping );
			if ( !columnNames.isEmpty() ) {
				keyDescriptor.forEachSelectable( (selectionIndex, selectableMapping) -> {
					resolveSqlSelection(
							columnNames.get( selectionIndex ),
							tableGroup.resolveTableReference(
									fetchPath,
									keyDescriptor.getKeyPart(),
									selectableMapping.getContainingTableExpression()
							),
							selectableMapping,
							jdbcResultsMetadata,
							domainResultCreationState
					); }
				);
			}

			// We process the fetch builder such that it contains a resultBuilderEntity before calling this method in ResultSetMappingProcessor
			if ( resultBuilderEntity != null ) {
				return resultBuilderEntity.buildFetch(
						parent,
						attributeMapping,
						jdbcResultsMetadata,
						creationState
				);
			}
		}
		try {
			final String prefix = DynamicResultBuilderEntityStandard.prefix( creationState, ELEMENT_PREFIX, INDEX_PREFIX );
			creationState.pushExplicitFetchMementoResolver(
					relativePath -> {
						if ( relativePath.startsWith( prefix ) ) {
							return findFetchBuilder( relativePath.substring( prefix.length() ) );
						}
						return null;
					}
			);
			return parent.generateFetchableFetch(
					attributeMapping,
					parent.resolveNavigablePath( attributeMapping ),
					FetchTiming.IMMEDIATE,
					true,
					null,
					domainResultCreationState
			);
		}
		finally {
			creationState.popExplicitFetchMementoResolver();
		}
	}

	private TableGroup tableGroup(
			NavigablePath fetchPath,
			AttributeMapping attributeMapping,
			TableGroup ownerTableGroup,
			DomainResultCreationStateImpl creationState) {
		if ( attributeMapping instanceof TableGroupJoinProducer tableGroupJoinProducer ) {
			final TableGroupJoin tableGroupJoin = tableGroupJoinProducer.createTableGroupJoin(
					fetchPath,
					ownerTableGroup,
					tableAlias,
					new SqlAliasBaseConstant( tableAlias ),
					SqlAstJoinType.INNER,
					true,
					false,
					creationState
			);
			ownerTableGroup.addTableGroupJoin( tableGroupJoin );
			final TableGroup tableGroup = tableGroupJoin.getJoinedGroup();
			creationState.getFromClauseAccess().registerTableGroup( fetchPath, tableGroup );
			return tableGroup;
		}
		else {
			return ownerTableGroup;
		}
	}

	private static ForeignKeyDescriptor getForeignKeyDescriptor(AttributeMapping attributeMapping) {
		if ( attributeMapping instanceof PluralAttributeMapping pluralAttributeMapping ) {
			return pluralAttributeMapping.getKeyDescriptor();
		}
		else if ( attributeMapping instanceof ToOneAttributeMapping toOneAttributeMapping ) {
			return toOneAttributeMapping.getForeignKeyDescriptor();
		}
		else {
			// Not sure if this fetch builder can also be used with other attribute mappings
			throw new AssertionFailure( "Unrecognized AttributeMapping" );
		}
	}

	private void resolveSqlSelection(
			String columnAlias,
			TableReference tableReference,
			SelectableMapping selectableMapping,
			JdbcValuesMetadata jdbcResultsMetadata,
			DomainResultCreationState domainResultCreationState) {
		final DomainResultCreationStateImpl creationStateImpl = impl( domainResultCreationState );
		creationStateImpl.resolveSqlSelection(
				ResultsHelper.resolveSqlExpression(
						creationStateImpl,
						jdbcResultsMetadata,
						tableReference,
						selectableMapping,
						columnAlias
				),
				selectableMapping.getJdbcMapping().getJdbcJavaType(),
				null,
				domainResultCreationState.getSqlAstCreationState().getCreationContext()
						.getSessionFactory().getTypeConfiguration()
		);
	}

	@Override
	public NativeQuery.ReturnProperty addColumnAlias(String columnAlias) {
		columnNames.add( columnAlias );
		return this;
	}

	@Override
	public List<String> getColumnAliases() {
		return columnNames;
	}

	@Override
	public NativeQuery.FetchReturn setLockMode(LockMode lockMode) {
		this.lockMode = lockMode;
		return this;
	}

	@Override
	public DynamicFetchBuilderLegacy addProperty(String propertyName, String columnAlias) {
		addProperty( propertyName ).addColumnAlias( columnAlias );
		return this;
	}

	@Override
	public DynamicFetchBuilder addProperty(String propertyName) {
		DynamicFetchBuilderStandard fetchBuilder = new DynamicFetchBuilderStandard( propertyName );
		fetchBuilderMap.put( propertyName, fetchBuilder );
		return fetchBuilder;
	}

	@Override
	public FetchBuilder findFetchBuilder(String fetchableName) {
		return fetchBuilderMap.get( fetchableName );
	}

	@Override
	public DynamicFetchBuilderContainer addProperty(String propertyName, String... columnAliases) {
		final DynamicFetchBuilder fetchBuilder = addProperty( propertyName );
		for ( String columnAlias : columnAliases ) {
			fetchBuilder.addColumnAlias( columnAlias );
		}
		return this;
	}

	@Override
	public void addFetchBuilder(String propertyName, FetchBuilder fetchBuilder) {
		fetchBuilderMap.put( propertyName, fetchBuilder );
	}

	@Override
	public void visitFetchBuilders(BiConsumer<String, FetchBuilder> consumer) {
		fetchBuilderMap.forEach( consumer );
	}

	@Override
	public boolean equals(Object o) {
		if ( this == o ) {
			return true;
		}
		if ( o == null || getClass() != o.getClass() ) {
			return false;
		}

		final DynamicFetchBuilderLegacy that = (DynamicFetchBuilderLegacy) o;
		return tableAlias.equals( that.tableAlias )
			&& ownerTableAlias.equals( that.ownerTableAlias )
			&& fetchableName.equals( that.fetchableName )
			&& Objects.equals( columnNames, that.columnNames )
			&& Objects.equals( fetchBuilderMap, that.fetchBuilderMap )
			&& Objects.equals( resultBuilderEntity, that.resultBuilderEntity );
	}

	@Override
	public int hashCode() {
		int result = tableAlias.hashCode();
		result = 31 * result + ownerTableAlias.hashCode();
		result = 31 * result + fetchableName.hashCode();
		result = 31 * result + ( columnNames != null ? columnNames.hashCode() : 0 );
		result = 31 * result + ( fetchBuilderMap != null ? fetchBuilderMap.hashCode() : 0 );
		result = 31 * result + ( resultBuilderEntity != null ? resultBuilderEntity.hashCode() : 0 );
		return result;
	}
}
