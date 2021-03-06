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
package com.squid.kraken.v4.core.analysis.engine.hierarchy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squid.core.concurrent.ExecutionManager;
import com.squid.core.expression.scope.ScopeException;
import com.squid.core.sql.model.SQLScopeException;
import com.squid.kraken.v4.caching.redis.queryworkerserver.QueryWorkerJobStatus;
import com.squid.kraken.v4.core.analysis.engine.hierarchy.DimensionIndex.Status;
import com.squid.kraken.v4.core.analysis.engine.query.HierarchyQuery;
import com.squid.kraken.v4.core.analysis.engine.query.mapping.DimensionMapping;
import com.squid.kraken.v4.model.DimensionPK;

/**
 * Compute the Domain Hierarchy...
 * 
 * @author sergefantino
 *
 */
public class DomainHierarchyCompute extends DomainHierarchyQueryGenerator {
	static final Logger logger = LoggerFactory.getLogger(DomainHierarchyCompute.class);

	private DomainHierarchy hierarchy;

	private HashMap<DimensionPK, ExecuteHierarchyQuery> jobLookup;
	private List<ExecuteHierarchyQuery> ongoingQueries;

	public DomainHierarchyCompute(DomainHierarchy hierarchy) {
		super(hierarchy);

		this.hierarchy = hierarchy;
		hierarchy.setCompute(this);
		try {
			// prepare the queries upfront since the ES indexes cannot work
			// until the mapping is initialized, and this is a side effect of
			// the query prep
			prepareQueries();
		} catch (ScopeException | SQLScopeException e) {
			// unable to run any query
			// need to provide some feedback to the user ?
			// result.setFatalError(e);
			this.hierarchy.setState(DomainHierarchy.State.CANCELLED);
		}

		jobLookup = new HashMap<DimensionPK, ExecuteHierarchyQuery>();
		ongoingQueries = new ArrayList<ExecuteHierarchyQuery>();
	}

	public DomainHierarchyCompute(DomainHierarchy hierarchy, DomainHierarchyCompute legacy) {
		this(hierarchy);

		if (legacy != null && !legacy.ongoingQueries.isEmpty()) {
			HashMap<DimensionPK, HierarchyQuery> trimmedQueries = new HashMap<DimensionPK, HierarchyQuery>();
			HashSet<ExecuteHierarchyQuery> validOngoingJobs = new HashSet<ExecuteHierarchyQuery>();

			for (DimensionPK dpk : this.queries.keySet()) {
				ExecuteHierarchyQuery query = legacy.jobLookup.get(dpk);
				if (query != null) {

					Future<ExecuteHierarchyQueryResult> job = query.getJob();
					if ((job != null) && (!job.isDone())
							&& (this.SQLQueryPerDimensionPK.get(dpk).equals(legacy.SQLQueryPerDimensionPK.get(dpk)))) {
						// ongoing queries that are still valid
						logger.info("Reusing ongoing query for dimension " + dpk);
						this.jobLookup.put(dpk, query);
						validOngoingJobs.add(query);
					} else {
						trimmedQueries.put(dpk, this.queries.get(dpk));
					}
				} else {
					trimmedQueries.put(dpk, this.queries.get(dpk));
				}
			}
			this.queries = trimmedQueries;
			this.ongoingQueries.addAll(validOngoingJobs);

			// cancel obsoletes jobs
			for (ExecuteHierarchyQuery query : legacy.ongoingQueries) {
				if (!validOngoingJobs.contains(query)) {
					query.getJob().cancel(true);
				}
			}
		}
	}

	public void computeEagerIndexes() {
		for (DimensionPK di : this.eagerIndexing) {
			this.computeIndex(di);
		}
	}

	public boolean computeIndex(DimensionIndex index) {
		return this.computeIndex(index.getDimension().getId());
	}

	private boolean computeIndex(DimensionPK index) {
		if (this.hierarchy.getState() == DomainHierarchy.State.CANCELLED) {
			// if prepareQueries() fails...
			return false;
		}

		this.hierarchy.setState(DomainHierarchy.State.STARTED);

		HierarchyQuery hq;
		synchronized (this.queries) {
			hq = this.queries.remove(index);
			if (hq == null) {
				return true;
			} else {

				ArrayList<DimensionPK> diList = new ArrayList<DimensionPK>(this.queries.keySet());
				for (DimensionPK di : diList) {
					if (this.queries.get(di).equals(hq)) {
						this.queries.remove(di);
					}
				}

			}
		}
		String customerId = hq.getSelect().getUniverse().getProject().getCustomerId();
		ExecuteHierarchyQuery newQuery = new ExecuteHierarchyQuery(hq);
		Future<ExecuteHierarchyQueryResult> future = ExecutionManager.INSTANCE.submit(customerId, newQuery);
		// keep an eye on it
		newQuery.setJob(future);

		ongoingQueries.add(newQuery);
		for (DimensionMapping m : hq.getDimensionMapping()) {
			jobLookup.put(m.getDimensionIndex().getDimension().getId(), newQuery);
		}
		return true;
	}

	/**
	 * check if the domainHierarchy is done (for every dimension)
	 * 
	 * @param timeoutMs
	 *            wait for timeout ms if > 0; if zero, won't block if not
	 *            complete; if NULL, will block until it's complete
	 * @return
	 * @throws InterruptedException
	 * @throws TimeoutException
	 * @throws ExecutionException
	 */
	public boolean isDone(Integer timeoutMs) throws InterruptedException, TimeoutException, ExecutionException {
		if (this.queries == null)
			return false;
		if (!this.queries.isEmpty()) {
			return false;
		} else {

			if (ongoingQueries == null)
				return false;
			long start = System.currentTimeMillis();
			long elapse = 0;
			for (ExecuteHierarchyQuery query : ongoingQueries) {
				Future<ExecuteHierarchyQueryResult> job = query.getJob();
				if (job != null && !job.isDone()) {
					if (timeoutMs == null) {
						job.get();
					} else {
						int remaining = timeoutMs - (int) elapse;
						if (remaining > 0) {
							job.get(remaining, TimeUnit.MILLISECONDS);// wait
																		// for
																		// completion
						} else {
							// computing still in progress
							return false;
						}
						elapse = System.currentTimeMillis() - start;
					}
				}
			}
			// ok
			return true;
		}
	}

	public boolean isDone(DimensionIndex index, Integer timeoutMs)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (index.getStatus() == Status.DONE || index.getStatus() == Status.ERROR)
			return true;
		if (jobLookup == null)
			return false;

		ExecuteHierarchyQuery ehq = jobLookup.get(index.getDimension().getId());
		if (ehq == null)
			return false;

		Future<ExecuteHierarchyQueryResult> job = ehq.getJob();
		if (job == null)
			return false;
		if (!job.isDone()) {
			if (timeoutMs == null) {
				job.get();
			} else if (timeoutMs > 0) {
				job.get(timeoutMs, TimeUnit.MILLISECONDS);
			} else {
				return false;
			}

		} else {
			ExecuteHierarchyQueryResult res = job.get();
			res.waitForIndexationCompletion(index, 5);
		}
		return (index.getStatus() == Status.DONE || index.getStatus() == Status.ERROR);
	}

	/**
	 * cancel the jobs execution
	 */
	public void cancel() {
		if (ongoingQueries != null) {
			for (ExecuteHierarchyQuery query : ongoingQueries) {
				if (query.getJob() != null)
					query.getJob().cancel(false);
			}
		}
	}

	public boolean cancel(String key) {

		for (ExecuteHierarchyQuery query : ongoingQueries) {
			if (query.getStatus().getKey().equals(key)) {
				if (query.getJob() != null)
					;
				{
					query.getJob().cancel(false);
					return true;
				}
			}
		}
		return false;
	}

	public List<QueryWorkerJobStatus> getOngoingQueriesStatus(String customerId) {

		ArrayList<QueryWorkerJobStatus> res = new ArrayList<QueryWorkerJobStatus>();

		for (Iterator<ExecuteHierarchyQuery> iter = this.ongoingQueries.listIterator(); iter.hasNext();) {
			ExecuteHierarchyQuery ehq = iter.next();
			if (ehq.isOngoing()) {
				QueryWorkerJobStatus jobStatus = ehq.getStatus();

				if (jobStatus.getProjectPK().getCustomerId().equals(customerId)) {
					res.add(ehq.getStatus());
				}
			} else {
				iter.remove();
			}
		}
		return res;
	}

}
