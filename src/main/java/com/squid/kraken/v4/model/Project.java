/*******************************************************************************
 * Copyright © Squid Solutions, 2016
 *
 * This file is part of Open Bouquet software.
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * There is a special FOSS exception to the terms and conditions of the 
 * licenses as they are applied to this program. See LICENSE.txt in
 * the directory of this program distribution.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * Squid Solutions also offers commercial licenses with additional warranties,
 * professional functionalities or services. If you purchase a commercial
 * license, then it supersedes and replaces any other agreement between
 * you and Squid Solutions (above licenses and LICENSE.txt included).
 * See http://www.squidsolutions.com/EnterpriseBouquet/
 *******************************************************************************/
package com.squid.kraken.v4.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;

import org.mongodb.morphia.annotations.Field;
import org.mongodb.morphia.annotations.Index;
import org.mongodb.morphia.annotations.Indexes;
import org.mongodb.morphia.annotations.Property;
import org.mongodb.morphia.annotations.Transient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.squid.kraken.v4.model.visitor.ModelVisitor;
import com.squid.kraken.v4.persistence.AppContext;
import com.squid.kraken.v4.persistence.DAOFactory;
import io.swagger.annotations.ApiModelProperty;

@XmlRootElement
@XmlType(namespace = "http://model.v4.kraken.squid.com")
@SuppressWarnings("serial")
@Indexes({ @Index(fields = { @Field(value = "id.customerId"),
		@Field(value = "id.projectId") }) })
public class Project extends LzPersistentBaseImpl<ProjectPK> implements
		HasConfig, Cloneable, HasChildren {
	
	private static String[] CHILDREN = { "domains", "relations", "bookmarks" };
	
	private String dbUrl;

	private String dbUser;

	private String dbPassword;

	private List<String> dbSchemas;
	
	private String inMemExt;

	@Transient
	transient private List<Domain> domains;

	@Transient
	transient private List<Relation> relations;
	
	@Transient
	transient private List<Bookmark> bookmarks;

	@JsonRawValue
	@Property
	private Object config;

	@Transient
	private Integer dbPasswordLength;

	private String dbVendorId;
	private Map<String, String> dbArguments;

	// T1771: support internal version for project
	public static final int VERSION_0 = 0;// for legacy
	public static final int VERSION_1 = 1;// changing the dynamic IDs to make them independent from the customer/project UUID
	
	// the version is now visible, but cannot be modified (the store method won't allow it)
	// - we need to export the project version if we want to re-create the project in a new instance, so the IDs are compliant
	// - if we hide the internalVerion, we won't be able to move an old project
	private Integer internalVersion = VERSION_0;

	/**
	 * Default constructor (required for jaxb).
	 */
	public Project() {
		this(null, null);
	}
	
	public Project(ProjectPK projectId) {
		this(projectId, null);
	}

	public Project(ProjectPK projectId, String name) {
		super(projectId, name);
	}

	/**
	 * @deprecated
	 * @param projectId
	 * @param name
	 * @param sandboxPath
	 */
	public Project(ProjectPK projectId, String name, Expression sandboxPath) {
		super(projectId, name);
	}
	
	@Override
	public Object clone() throws CloneNotSupportedException {
		return super.clone();
	}

	/**
	 * Visitor.
	 */
	@XmlTransient
	@JsonIgnore
	public void accept(ModelVisitor visitor) {
		visitor.visit(this);
		for (Domain o : getDomains()) {
			o.accept(visitor);
		}

		for (Relation o : getRelations()) {
			o.accept(visitor);
		}
		
		for (Bookmark o : getBookmarks()) {
			o.accept(visitor);
		}
	}

	@Override
	public ProjectPK getId() {
		return super.getId();
	}

	@Override
	public Persistent<?> getParentObject(AppContext ctx) {
		return DAOFactory.getDAOFactory().getDAO(Customer.class)
				.readNotNull(ctx, new CustomerPK(ctx.getCustomerId()));
	}

	@ApiModelProperty(value = "The DataBase vendor ID to use for connecting")
	public String getDbVendorId() {
		return dbVendorId;
	}
	
	/**
	 * @param dbVendorId the dbVendorId to set
	 */
	public void setDbVendorId(String dbVendorId) {
		this.dbVendorId = dbVendorId;
	}

	@ApiModelProperty(value = "The DataBase JDBC URL arguments")
	public Map<String, String> getDbArguments() {
		return dbArguments;
	}
	
	public void setDbArguments(Map<String, String> arguments) {
		this.dbArguments = arguments;
	}

	@ApiModelProperty(value = "The DataBase JDBC URL (requires WRITE role to view)")
	public String getDbUrl() {
		return dbUrl;
	}

	public void setDbUrl(String dbUrl) {
		this.dbUrl = dbUrl;
	}

	@ApiModelProperty(value = "The DataBase JDBC user (requires WRITE role to view)")
	public String getDbUser() {
		return dbUser;
	}

	public void setDbUser(String dbUser) {
		this.dbUser = dbUser;
	}

	@ApiModelProperty(value = "The DataBase JDBC password (write-only)")
	public String getDbPassword() {
		return dbPassword;
	}

	public void setDbPassword(String dbPassword) {
		this.dbPassword = dbPassword;
	}

	@ApiModelProperty(value = "The DataBase Schemas enabled (requires WRITE role). "
			+ "The list of available discovered Schemas can be found via the {projectId}/schemas-suggestion method")
	public List<String> getDbSchemas() {
		return (dbSchemas == null) ? Collections.<String> emptyList()
				: dbSchemas;
	}

	public void setDbSchemas(List<String> dbSchemas) {
		this.dbSchemas = dbSchemas;
	}

	@ApiModelProperty(value = "If the Project is using In Memory Extension")
	public String getUsingInMemExt() {
		return inMemExt;
	}
	
	public void setUsingInMemExt(String inMemExt) {
		this.inMemExt = inMemExt;
	}
	
	public List<Domain> getDomains() {
		return (domains == null) ? Collections.<Domain> emptyList() : domains;
	}

	public void setDomains(List<Domain> domains) {
		this.domains = domains;
	}

	public List<Relation> getRelations() {
		return (relations == null) ? Collections.<Relation> emptyList()
				: relations;
	}

	public void setRelations(List<Relation> relations) {
		this.relations = relations;
	}

	public List<Bookmark> getBookmarks() {
		return (bookmarks == null) ? Collections.<Bookmark> emptyList()
				: bookmarks;
	}

	public void setBookmarks(List<Bookmark> bookmarks) {
		this.bookmarks = bookmarks;
	}

	/**
	 * Get the "dbPasswordLength" variable.
	 * 
	 * @return the dbPasswordLength
	 */
	@ApiModelProperty(value = "The DataBase JDBC password length")
	@XmlElement
	@JsonProperty
	public Integer getDbPasswordLength() {
		return dbPasswordLength;
	}

	@XmlTransient
	@JsonIgnore
	public void setDbPasswordLength(Integer dbPasswordLength) {
		this.dbPasswordLength = dbPasswordLength;
	}

	/**
	 * An optional configuration (json) object.
	 * 
	 * @return a json String or null
	 * @deprecated by {@link Shortcut}
	 */
	@Deprecated
	@ApiModelProperty(value = "Deprecated : An optional configuration (json) object")
	public String getConfig() {
		if (this.config != null) {
			return this.config.toString();
		} else {
			return null;
		}
	}

	public void setConfig(JsonNode node) {
		String t;
		if (node != null) {
			t = node.toString();
		} else {
			t = null;
		}
		this.config = t;
	}
	
	@Override
	public String[] getChildren() {
		return CHILDREN;
	}
    
    /**
	 * @return the internalVersion
	 */
	public Integer getInternalVersion() {
		return internalVersion;
	}
	
	/**
	 * use the copy internalVersion. If copy is NULL, use the newest version.
	 * @param copy
	 */
	public void copyInternalVersion(Project copy) {
		if (copy==null) {
			this.internalVersion = VERSION_1;
		} else {
			this.internalVersion = copy.internalVersion;
		}
	}

}
