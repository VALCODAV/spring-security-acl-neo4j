package org.springframework.security.acls.neo4j;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.neo4j.graphdb.GraphDatabaseService;
import org.springframework.data.neo4j.conversion.Result;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.security.acls.domain.AccessControlEntryImpl;
import org.springframework.security.acls.domain.AclAuthorizationStrategy;
import org.springframework.security.acls.domain.AclImpl;
import org.springframework.security.acls.domain.DefaultPermissionFactory;
import org.springframework.security.acls.domain.GrantedAuthoritySid;
import org.springframework.security.acls.domain.ObjectIdentityImpl;
import org.springframework.security.acls.domain.PermissionFactory;
import org.springframework.security.acls.domain.PrincipalSid;
import org.springframework.security.acls.jdbc.LookupStrategy;
import org.springframework.security.acls.model.AccessControlEntry;
import org.springframework.security.acls.model.Acl;
import org.springframework.security.acls.model.AclCache;
import org.springframework.security.acls.model.MutableAcl;
import org.springframework.security.acls.model.NotFoundException;
import org.springframework.security.acls.model.ObjectIdentity;
import org.springframework.security.acls.model.Permission;
import org.springframework.security.acls.model.PermissionGrantingStrategy;
import org.springframework.security.acls.model.Sid;
import org.springframework.security.acls.model.UnloadedSidException;
import org.springframework.security.util.FieldUtils;
import org.springframework.util.Assert;

/**
 * Neo4j based Implementation of Lookup Strategy
 * 
 * @author shazin
 *
 */
public class Neo4jLookupStrategy implements LookupStrategy {

	private final String DEFAULT_MATCH_CLAUSE = "MATCH (owner:SidNode)<-[:OWNED_BY]-(acl:AclNode)-[:SECURES]->(class:ClassNode) OPTIONAL MATCH (acl)<-[:COMPOSES]-(ace:AceNode)-[:AUTHORIZES]->(sid:SidNode) WITH acl, ace, owner, sid, class WHERE ( ";
	private final String DEFAULT_RETURN_CLAUSE = " ) RETURN owner.principal AS aclPrincipal, owner.sid AS aclSid, acl.objectIdIdentity AS objectIdIdentity, ace.aceOrder AS aceOrder, acl.id AS aclId, acl.parentObject AS parentObject, acl.entriesInheriting AS entriesInheriting, ace.id AS aceId, ace.mask AS mask, ace.granting AS granting, ace.auditSuccess AS auditSuccess, ace.auditFailure AS auditFailure, sid.principal AS acePrincipal, sid.sid AS aceSid, class.className AS className ";
	private final String DEFAULT_WHERE_CLAUSE = " (acl.objectIdIdentity = {objectIdIdentity%d} AND class.className = {className%d}) ";
	private final String DEFAULT_OBJ_ID_LOOKUP_WHERE_CLAUSE = " (acl.id = {aclId%d}) ";
	private final String DEFAULT_ORDER_BY_CLAUSE = " ORDER BY acl.objectIdIdentity ASC, ace.aceOrder ASC";

	private final AclCache aclCache;
	private PermissionFactory permissionFactory = new DefaultPermissionFactory();
	private PermissionGrantingStrategy permissionGrantingStrategy;
	private final AclAuthorizationStrategy aclAuthorizationStrategy;
	private Neo4jTemplate neo4jTemplate;
	private int batchSize = 50;
	private String lookupObjectIdentitiesWhereClause = DEFAULT_OBJ_ID_LOOKUP_WHERE_CLAUSE;
	private String defaultWhereClause = DEFAULT_WHERE_CLAUSE;
	private String matchClause = DEFAULT_MATCH_CLAUSE;
	private String orderByClause = DEFAULT_ORDER_BY_CLAUSE;
	private String returnClause = DEFAULT_RETURN_CLAUSE;

	private final Field fieldAces = FieldUtils.getField(AclImpl.class, "aces");
	private final Field fieldAcl = FieldUtils.getField(
			AccessControlEntryImpl.class, "acl");

	/**
	 * Constructor
	 * 
	 * @param graphDatabaseService - Graph Database Service
	 * @param aclCache - Acl Cache
	 * @param aclAuthorizationStrategy - Acl Authorization Strategy
	 * @param permissionGrantingStrategy - Permission Granting Strategy
	 */
	public Neo4jLookupStrategy(GraphDatabaseService graphDatabaseService,
			AclCache aclCache,
			AclAuthorizationStrategy aclAuthorizationStrategy,
			PermissionGrantingStrategy permissionGrantingStrategy) {
		Assert.notNull(aclCache, "AclCache required");
		Assert.notNull(aclAuthorizationStrategy,
				"AclAuthorizationStrategy required");
		Assert.notNull(permissionGrantingStrategy,
				"permissionGrantingStrategy required");
		this.aclCache = aclCache;
		this.aclAuthorizationStrategy = aclAuthorizationStrategy;
		this.permissionGrantingStrategy = permissionGrantingStrategy;
		this.neo4jTemplate = new Neo4jTemplate(graphDatabaseService);
		fieldAces.setAccessible(true);
		fieldAcl.setAccessible(true);
	}

	/**
	 * Read Acls By Identities and Sids
	 */
	@Override
	public Map<ObjectIdentity, Acl> readAclsById(List<ObjectIdentity> objects,
			List<Sid> sids) {
		Assert.isTrue(batchSize >= 1, "BatchSize must be >= 1");
		Assert.notEmpty(objects, "Objects to lookup required");

		// Map<ObjectIdentity,Acl>
		Map<ObjectIdentity, Acl> result = new HashMap<ObjectIdentity, Acl>(); // contains
																				// FULLY
																				// loaded
																				// Acl
																				// objects

		Set<ObjectIdentity> currentBatchToLoad = new HashSet<ObjectIdentity>();

		for (int i = 0; i < objects.size(); i++) {
			final ObjectIdentity oid = objects.get(i);
			boolean aclFound = false;

			// Check we don't already have this ACL in the results
			if (result.containsKey(oid)) {
				aclFound = true;
			}

			// Check cache for the present ACL entry
			if (!aclFound) {
				Acl acl = aclCache.getFromCache(oid);

				// Ensure any cached element supports all the requested SIDs
				// (they should always, as our base impl doesn't filter on SID)
				if (acl != null) {
					if (acl.isSidLoaded(sids)) {
						result.put(acl.getObjectIdentity(), acl);
						aclFound = true;
					} else {
						throw new IllegalStateException(
								"Error: SID-filtered element detected when implementation does not perform SID filtering "
										+ "- have you added something to the cache manually?");
					}
				}
			}

			// Load the ACL from the database
			if (!aclFound) {
				currentBatchToLoad.add(oid);
			}

			// Is it time to load from JDBC the currentBatchToLoad?
			if ((currentBatchToLoad.size() == this.batchSize)
					|| ((i + 1) == objects.size())) {
				if (currentBatchToLoad.size() > 0) {
					Map<ObjectIdentity, Acl> loadedBatch = lookupObjectIdentities(
							currentBatchToLoad, sids);

					// Add loaded batch (all elements 100% initialized) to
					// results
					result.putAll(loadedBatch);

					// Add the loaded batch to the cache

					for (Acl loadedAcl : loadedBatch.values()) {
						aclCache.putInCache((AclImpl) loadedAcl);
					}

					currentBatchToLoad.clear();
				}
			}
		}

		return result;
	}

	/**
	 * Lookup Object Identities
	 * 
	 * @param objectIdentities - Object Identities
	 * @param sids - Sids
	 * @return Object Identity Acl Map
	 */
	private Map<ObjectIdentity, Acl> lookupObjectIdentities(
			final Collection<ObjectIdentity> objectIdentities, List<Sid> sids) {
		Assert.notEmpty(objectIdentities, "Must provide identities to lookup");

		final Map<Serializable, Acl> acls = new HashMap<Serializable, Acl>(); // contains
																				// Acls
																				// with
																				// StubAclParents

		// Make the "acls" map contain all requested objectIdentities
		// (including markers to each parent in the hierarchy)
		int requiredRepetitions = objectIdentities.size();
		final String startSql = matchClause;

		final String endSql = returnClause + orderByClause;

		StringBuilder sqlStringBldr = new StringBuilder(startSql.length()
				+ endSql.length() + requiredRepetitions
				* (defaultWhereClause.length() + 4));
		sqlStringBldr.append(startSql);

		for (int i = 1; i <= requiredRepetitions; i++) {
			sqlStringBldr.append(String.format(defaultWhereClause, i, i));

			if (i != requiredRepetitions) {
				sqlStringBldr.append(" OR ");
			}
		}

		sqlStringBldr.append(endSql);
		String sql = sqlStringBldr.toString();

		Map<String, Object> params = new HashMap<String, Object>();
		int index = 1;
		for (ObjectIdentity oid : objectIdentities) {
			params.put(String.format("objectIdIdentity%d", index),
					(Long) oid.getIdentifier());
			params.put(String.format("className%d", index++), oid.getType());
		}

		Result<Map<String, Object>> queryResult = neo4jTemplate.query(sql,
				params);

		Set<String> parentsToLookup = new ProcessResult(acls, sids, queryResult)
				.extractData();

		// Lookup the parents, now that our JdbcTemplate has released the
		// database connection (SEC-547)
		if (parentsToLookup.size() > 0) {
			lookupPrimaryKeys(acls, parentsToLookup, sids);
		}

		// Finally, convert our "acls" containing StubAclParents into true Acls
		Map<ObjectIdentity, Acl> resultMap = new HashMap<ObjectIdentity, Acl>();

		for (Acl inputAcl : acls.values()) {
			Assert.isInstanceOf(AclImpl.class, inputAcl,
					"Map should have contained an AclImpl");
			Assert.isInstanceOf(String.class, ((AclImpl) inputAcl).getId(),
					"Acl.getId() must be String");

			Acl result = convert(acls, (String) ((AclImpl) inputAcl).getId());
			resultMap.put(result.getObjectIdentity(), result);
		}

		return resultMap;
	}

	/**
	 * Lookup Primary Keys
	 * 
	 * @param acls - Acls
	 * @param findNow - Find now Acls
	 * @param sids - Sids
	 */
	private void lookupPrimaryKeys(final Map<Serializable, Acl> acls,
			final Set<String> findNow, final List<Sid> sids) {
		Assert.notNull(acls, "ACLs are required");
		Assert.notEmpty(findNow, "Items to find now required");

		// Make the "acls" map contain all requested objectIdentities
		// (including markers to each parent in the hierarchy)
		int requiredRepetitions = findNow.size();
		final String startSql = matchClause;

		final String endSql = returnClause + orderByClause;

		StringBuilder sqlStringBldr = new StringBuilder(startSql.length()
				+ endSql.length() + requiredRepetitions
				* (lookupObjectIdentitiesWhereClause.length() + 4));
		sqlStringBldr.append(startSql);

		for (int i = 1; i <= requiredRepetitions; i++) {
			sqlStringBldr.append(String.format(
					lookupObjectIdentitiesWhereClause, i));

			if (i != requiredRepetitions) {
				sqlStringBldr.append(" OR ");
			}
		}

		sqlStringBldr.append(endSql);
		String sql = sqlStringBldr.toString();

		Map<String, Object> params = new HashMap<String, Object>();
		int index = 1;
		for (String id : findNow) {
			params.put(String.format("aclId%d", index++), id);
		}

		Result<Map<String, Object>> queryResult = neo4jTemplate.query(sql,
				params);

		Set<String> parentsToLookup = new ProcessResult(acls, sids, queryResult)
				.extractData();

		// Lookup the parents, now that our JdbcTemplate has released the
		// database connection (SEC-547)
		if (parentsToLookup.size() > 0) {
			lookupPrimaryKeys(acls, parentsToLookup, sids);
		}
	}

	/**
	 * Convert data to Acl
	 * 
	 * @param inputMap - Input Data map
	 * @param currentIdentity - Current Identity
	 * @return acl 
	 */
	private AclImpl convert(Map<Serializable, Acl> inputMap,
			String currentIdentity) {
		Assert.notEmpty(inputMap, "InputMap required");
		Assert.notNull(currentIdentity, "CurrentIdentity required");

		// Retrieve this Acl from the InputMap
		Acl uncastAcl = inputMap.get(currentIdentity);
		Assert.isInstanceOf(AclImpl.class, uncastAcl,
				"The inputMap contained a non-AclImpl");

		AclImpl inputAcl = (AclImpl) uncastAcl;

		Acl parent = inputAcl.getParentAcl();

		if ((parent != null) && parent instanceof StubAclParent) {
			// Lookup the parent
			StubAclParent stubAclParent = (StubAclParent) parent;
			parent = convert(inputMap, stubAclParent.getId());
		}

		// Now we have the parent (if there is one), create the true AclImpl
		AclImpl result = new AclImpl(inputAcl.getObjectIdentity(),
				(String) inputAcl.getId(), aclAuthorizationStrategy,
				permissionGrantingStrategy, parent, null,
				inputAcl.isEntriesInheriting(), inputAcl.getOwner());

		// Copy the "aces" from the input to the destination

		// Obtain the "aces" from the input ACL
		List<AccessControlEntryImpl> aces = readAces(inputAcl);

		// Create a list in which to store the "aces" for the "result" AclImpl
		// instance
		List<AccessControlEntryImpl> acesNew = new ArrayList<AccessControlEntryImpl>();

		// Iterate over the "aces" input and replace each nested
		// AccessControlEntryImpl.getAcl() with the new "result" AclImpl
		// instance
		// This ensures StubAclParent instances are removed, as per SEC-951
		for (AccessControlEntryImpl ace : aces) {
			setAclOnAce(ace, result);
			acesNew.add(ace);
		}

		// Finally, now that the "aces" have been converted to have the "result"
		// AclImpl instance, modify the "result" AclImpl instance
		setAces(result, acesNew);

		return result;
	}

	/**
	 * Stub Acl Parent
	 * 
	 * @author shazin
	 *
	 */
	private class StubAclParent implements Acl {
		private final String id;

		public StubAclParent(String id) {
			this.id = id;
		}

		public List<AccessControlEntry> getEntries() {
			throw new UnsupportedOperationException("Stub only");
		}

		public String getId() {
			return id;
		}

		public ObjectIdentity getObjectIdentity() {
			throw new UnsupportedOperationException("Stub only");
		}

		public Sid getOwner() {
			throw new UnsupportedOperationException("Stub only");
		}

		public Acl getParentAcl() {
			throw new UnsupportedOperationException("Stub only");
		}

		public boolean isEntriesInheriting() {
			throw new UnsupportedOperationException("Stub only");
		}

		public boolean isGranted(List<Permission> permission, List<Sid> sids,
				boolean administrativeMode) throws NotFoundException,
				UnloadedSidException {
			throw new UnsupportedOperationException("Stub only");
		}

		public boolean isSidLoaded(List<Sid> sids) {
			throw new UnsupportedOperationException("Stub only");
		}
	}

	/**
	 * Read Aces from Acl
	 * 
	 * @param acl - Acl
	 * @return List of Aces
	 */
	@SuppressWarnings("unchecked")
	private List<AccessControlEntryImpl> readAces(AclImpl acl) {
		try {
			return (List<AccessControlEntryImpl>) fieldAces.get(acl);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not obtain AclImpl.aces field", e);
		}
	}

	/**
	 * Set Acl on Ace
	 * 
	 * @param ace - Access Control Entry
	 * @param acl - Access Control List
	 */
	private void setAclOnAce(AccessControlEntryImpl ace, AclImpl acl) {
		try {
			fieldAcl.set(ace, acl);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not or set AclImpl on AccessControlEntryImpl fields",
					e);
		}
	}

	/**
	 * Set Aces for Acl
	 * 
	 * @param acl - Access Control List
	 * @param aces - Access Control Entries
	 */
	private void setAces(AclImpl acl, List<AccessControlEntryImpl> aces) {
		try {
			fieldAces.set(acl, aces);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Could not set AclImpl entries", e);
		}
	}

	/**
	 * Process Result 
	 * 
	 * @author shazin
	 *
	 */
	private class ProcessResult {
		private final Map<Serializable, Acl> acls;
		private final List<Sid> sids;
		private final Result<Map<String, Object>> result;

		public ProcessResult(Map<Serializable, Acl> acls, List<Sid> sids,
				Result<Map<String, Object>> result) {
			Assert.notNull(acls, "ACLs cannot be null");
			this.acls = acls;
			this.sids = sids; // can be null
			this.result = result;
		}

		public Set<String> extractData() {
			Set<String> parentIdsToLookup = new HashSet<String>(); // Set of
																	// parent_id
																	// Longs
			Iterator<Map<String, Object>> rs = result.iterator();
			Map<String, Object> data = null;
			while (rs.hasNext()) {
				// Convert current row into an Acl (albeit with a StubAclParent)
				data = rs.next();
				convertCurrentResultIntoObject(acls, data);

				// Figure out if this row means we need to lookup another parent
				Object parentId = data.get("parentObject");

				if (parentId != null) {
					// See if it's already in the "acls"
					if (acls.containsKey(parentId.toString())) {
						continue; // skip this while iteration
					}

					// Now try to find it in the cache
					MutableAcl cached = aclCache.getFromCache(parentId
							.toString());

					if ((cached == null) || !cached.isSidLoaded(sids)) {
						parentIdsToLookup.add(parentId.toString());
					} else {
						// Pop into the acls map, so our convert method doesn't
						// need to deal with an unsynchronized AclCache
						acls.put(cached.getId(), cached);
					}
				}
			}

			// Return the parents left to lookup to the caller
			return parentIdsToLookup;
		}

		private void convertCurrentResultIntoObject(
				Map<Serializable, Acl> acls, Map<String, Object> rs) {
			String id = rs.get("aclId").toString();

			// If we already have an ACL for this ID, just create the ACE
			Acl acl = acls.get(id);

			if (acl == null) {
				// Make an AclImpl and pop it into the Map
				ObjectIdentity objectIdentity = new ObjectIdentityImpl(rs.get(
						"className").toString(), Long.valueOf(rs.get(
						"objectIdIdentity").toString()));

				Acl parentAcl = null;
				Object parentAclId = rs.get("parentObject");

				if (parentAclId != null) {
					parentAcl = new StubAclParent(parentAclId.toString());
				}

				boolean entriesInheriting = Boolean.valueOf(rs.get(
						"entriesInheriting").toString());
				Sid owner;

				if (Boolean.valueOf(rs.get("aclPrincipal").toString())) {
					owner = new PrincipalSid(rs.get("aclSid").toString());
				} else {
					owner = new GrantedAuthoritySid(rs.get("aclSid").toString());
				}

				acl = new AclImpl(objectIdentity, id, aclAuthorizationStrategy,
						permissionGrantingStrategy, parentAcl, null,
						entriesInheriting, owner);

				acls.put(id, acl);
			}

			// Add an extra ACE to the ACL (ORDER BY maintains the ACE list
			// order)
			// It is permissible to have no ACEs in an ACL (which is detected by
			// a null ACE_SID)
			if (rs.get("aceSid") != null) {
				String aceId = rs.get("aceId").toString();
				Sid recipient;

				if (Boolean.valueOf(rs.get("acePrincipal").toString())) {
					recipient = new PrincipalSid(rs.get("aceSid").toString());
				} else {
					recipient = new GrantedAuthoritySid(rs.get("aceSid")
							.toString());
				}

				int mask = Integer.parseInt(rs.get("mask").toString());
				Permission permission = permissionFactory.buildFromMask(mask);
				boolean granting = Boolean.valueOf(rs.get("granting")
						.toString());
				boolean auditSuccess = Boolean.valueOf(rs.get("auditSuccess")
						.toString());
				boolean auditFailure = Boolean.valueOf(rs.get("auditFailure")
						.toString());

				AccessControlEntryImpl ace = new AccessControlEntryImpl(aceId,
						acl, recipient, permission, granting, auditSuccess,
						auditFailure);

				// Field acesField = FieldUtils.getField(AclImpl.class, "aces");
				List<AccessControlEntryImpl> aces = readAces((AclImpl) acl);

				// Add the ACE if it doesn't already exist in the ACL.aces field
				if (!aces.contains(ace)) {
					aces.add(ace);
				}
			}
		}
	}

	/**
	 * Get Permission Factory
	 * 
	 * @return permissionFactory
	 */
	public PermissionFactory getPermissionFactory() {
		return permissionFactory;
	}

	/**
	 * Set Permission Factory
	 * 
	 * @param permissionFactory
	 */
	public void setPermissionFactory(PermissionFactory permissionFactory) {
		this.permissionFactory = permissionFactory;
	}

	/**
	 * Get Permission Granting Strategy
	 * 
	 * @return permissionGrantingStrategy
	 */
	public PermissionGrantingStrategy getPermissionGrantingStrategy() {
		return permissionGrantingStrategy;
	}

	/**
	 * Set Permission Granting Strategy
	 * 
	 * @param permissionGrantingStrategy
	 */
	public void setPermissionGrantingStrategy(
			PermissionGrantingStrategy permissionGrantingStrategy) {
		this.permissionGrantingStrategy = permissionGrantingStrategy;
	}

	/**
	 * Get Neo4j Template
	 * 
	 * @return neo4jTemplate
	 */
	public Neo4jTemplate getNeo4jTemplate() {
		return neo4jTemplate;
	}

	/**
	 * Set Neo4j Template
	 * 
	 * @param neo4jTemplate
	 */
	public void setNeo4jTemplate(Neo4jTemplate neo4jTemplate) {
		this.neo4jTemplate = neo4jTemplate;
	}

	/**
	 * Get Batch Size
	 * 
	 * @return batchSize
	 */
	public int getBatchSize() {
		return batchSize;
	}

	/**
	 * Set Batch Size
	 * 
	 * @param batchSize
	 */
	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	/**
	 * Get Lookup Object Identities Where Clause
	 * 
	 * @return lookupObjectIdentitiesWhereClause
	 */
	public String getLookupObjectIdentitiesWhereClause() {
		return lookupObjectIdentitiesWhereClause;
	}

	/**
	 * Set Lookup Object Identities Where Clause
	 * 
	 * @param lookupObjectIdentitiesWhereClause
	 */
	public void setLookupObjectIdentitiesWhereClause(
			String lookupObjectIdentitiesWhereClause) {
		this.lookupObjectIdentitiesWhereClause = lookupObjectIdentitiesWhereClause;
	}

	/**
	 * Get Default Where Clause
	 * 
	 * @return defaultWhereClause
	 */
	public String getDefaultWhereClause() {
		return defaultWhereClause;
	}

	/**
	 * Set Default Where Clause
	 * 
	 * @param defaultWhereClause
	 */
	public void setDefaultWhereClause(String defaultWhereClause) {
		this.defaultWhereClause = defaultWhereClause;
	}

	/**
	 * Get Match Clause
	 * 
	 * @return matchClause
	 */
	public String getMatchClause() {
		return matchClause;
	}

	/**
	 * Set Match Clause
	 * 
	 * @param matchClause
	 */
	public void setMatchClause(String matchClause) {
		this.matchClause = matchClause;
	}

	/**
	 * Get Order by Clause
	 * 
	 * @return orderByClause
	 */
	public String getOrderByClause() {
		return orderByClause;
	}

	/**
	 * Set Order by Clause
	 * 
	 * @param orderByClause
	 */
	public void setOrderByClause(String orderByClause) {
		this.orderByClause = orderByClause;
	}

	/**
	 * Get Return Clause
	 * 
	 * @return returnClause
	 */
	public String getReturnClause() {
		return returnClause;
	}

	/**
	 * Set Return Clause
	 * 
	 * @param returnClause
	 */
	public void setReturnClause(String returnClause) {
		this.returnClause = returnClause;
	}

	/**
	 * Get Acl Cache
	 * 
	 * @return aclCache
	 */
	public AclCache getAclCache() {
		return aclCache;
	}

	/**
	 * Get Acl Authorization Strategy
	 * 
	 * @return aclAuthorizationStrategy
	 */
	public AclAuthorizationStrategy getAclAuthorizationStrategy() {
		return aclAuthorizationStrategy;
	}

}
