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

import javax.xml.bind.annotation.XmlRootElement;

@SuppressWarnings("serial")
@XmlRootElement
public class ProjectFacetJobPK extends ProjectPK {

    private String facetJobId;

    public ProjectFacetJobPK() {
        super();
    }

    public ProjectFacetJobPK(ProjectPK parent, String jobId) {
        this(parent.getCustomerId(), parent.getProjectId(), jobId);
    }

    public ProjectFacetJobPK(String customerId, String projectId, String jobId) {
        super(customerId, projectId);
        this.facetJobId = jobId;
    }

    public String getFacetJobId() {
        return facetJobId;
    }

    public void setFacetJobId(String jobId) {
        this.facetJobId = jobId;
    }

    @Override
    public String getObjectId() {
        return facetJobId;
    }

    @Override
    public void setObjectId(String id) {
        facetJobId = id;
    }

    @Override
    public ProjectPK getParent() {
        return new ProjectPK(getCustomerId(), getProjectId());
    }

    @Override
    public void setParent(GenericPK pk) {
        setCustomerId(((ProjectPK) pk).getCustomerId());
        setProjectId(((ProjectPK) pk).getProjectId());
    }

    @Override
    public String toString() {
        return "ProjectFacetJobPK [facetJobId=" + getFacetJobId() + ", getProjectId()=" + getProjectId()
                + ", getCustomerId()=" + getCustomerId() + "]";
    }
    
}
