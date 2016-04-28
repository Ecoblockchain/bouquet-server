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
package com.squid.kraken.v4.core.analysis.datamatrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.squid.core.domain.operators.ExtendedType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squid.core.database.model.Database;
import com.squid.core.domain.IDomain;
import com.squid.core.expression.ExpressionAST;
import com.squid.core.expression.scope.ScopeException;
import com.squid.core.sql.render.IOrderByPiece.ORDERING;
import com.squid.kraken.v4.caching.redis.datastruct.RawMatrix;
import com.squid.kraken.v4.caching.redis.datastruct.RawRow;
import com.squid.kraken.v4.core.analysis.engine.hierarchy.DimensionIndex;
import com.squid.kraken.v4.core.analysis.engine.hierarchy.DimensionMember;
import com.squid.kraken.v4.core.analysis.engine.processor.ComputingException;
import com.squid.kraken.v4.core.analysis.engine.query.mapping.AxisMapping;
import com.squid.kraken.v4.core.analysis.engine.query.mapping.MeasureMapping;
import com.squid.kraken.v4.core.analysis.engine.query.mapping.QueryMapper;
import com.squid.kraken.v4.core.analysis.model.DashboardSelection;
import com.squid.kraken.v4.core.analysis.model.DomainSelection;
import com.squid.kraken.v4.core.analysis.model.OrderBy;
import com.squid.kraken.v4.core.analysis.universe.Axis;
import com.squid.kraken.v4.core.analysis.universe.Measure;
import com.squid.kraken.v4.model.DataTable;
import com.squid.kraken.v4.model.DataTable.Col;
import com.squid.kraken.v4.model.DataTable.Col.DataType;
import com.squid.kraken.v4.model.Dimension;
import com.squid.kraken.v4.model.Dimension.Type;
import com.squid.kraken.v4.model.DimensionPK;
import com.squid.kraken.v4.model.Metric;
import com.squid.kraken.v4.persistence.AppContext;

import com.squid.kraken.v4.core.analysis.universe.Property;

/**
 * A DataMatrix stores the result of a query. There are two part in the Matrix:
 * the AxisColumn storing metadata about the dimension, and the facts
 * 
 * @author sfantino
 *
 */
public class DataMatrix {

	static final Logger logger = LoggerFactory.getLogger(DataMatrix.class);
	// private int size = 0;
	private boolean fullset = false;// only true if we read all the data
	private boolean isSorted = false;// true when sorted

	private Database database = null;// keep an eye on the database to check if
										// results are stale
	private DataMatrix parent = null;

	private List<RawRow> rows = new ArrayList<RawRow>();

	private boolean fromCache = false;// true if the data come from the cache

	private Date executionDate = null;// when did we compute the data

	private String redisKey;
	
	private List<Measure> kpis = new ArrayList<Measure>();
	private List<AxisValues> axes = new ArrayList<AxisValues>();

	private int[] axesIndirection;
	private int[] dataIndirection;

	private Map<Property, String> propertyToAlias;
	private Map<Property, Integer> propertyToType;

	public String getRedisKey() {
		return redisKey;
	}

	public void setRedisKey(String redisKey) {
		this.redisKey = redisKey;
	}

	public DataMatrix(Database db) {
		this.database = db;
		this.propertyToAlias = new HashMap<Property, String>();
		this.propertyToType = new HashMap<Property, Integer>();

	}

	/**
	 * create a matrix using the parent layout but with a new list of rows. Note that the parent 
	 * @param db
	 * @param rows
	 */
	public DataMatrix(Database db, List<RawRow> rows) {
		this.database = db;
		this.rows = rows;
		this.fullset = true;
		this.propertyToAlias = new HashMap<Property, String>();
		this.propertyToType = new HashMap<Property, Integer>();

	}

	/**
	 * create a matrix with same layout but empty data. We keep a link to the
	 * original matrix so we can check if a particular column is a sub-set of
	 * the original one
	 * 
	 * @param dm
	 */
	public DataMatrix(DataMatrix parent) {
		this.database = parent.database;
		this.parent = parent;
		for (AxisValues d : parent.getAxes()) {
			add(new AxisValues(d));
		}
		this.kpis.addAll(parent.getKPIs());
		this.fromCache = parent.fromCache;
		this.executionDate = parent.executionDate;
		this.fullset = parent.fullset;
		this.propertyToAlias = parent.propertyToAlias;
		this.propertyToType = parent.propertyToType;
		this.axesIndirection = parent.axesIndirection;
		this.dataIndirection = parent.dataIndirection;
	}

	public DataMatrix getParent() {
		return parent;
	}

	public DataMatrix(Database database, RawMatrix rawMatrix, QueryMapper mapper) throws ScopeException {
		// logger.info( rawMatrix.toString());
		this(database);
		// logger.info("compute data matrix from RawMatrix");
		this.setFromCache(rawMatrix.isFromCache());
		this.setExecutionDate(rawMatrix.getExecutionDate());
		this.setRedisKey(rawMatrix.getRedisKey());
		this.setFullset(!rawMatrix.hasMoreData());

		// init mappings
		axesIndirection = new int[mapper.getAxisMapping().size()];
		int i = 0;
		for (AxisMapping m : mapper.getAxisMapping()) {
			int index = rawMatrix.getColNames().indexOf(m.getPiece().getAlias());
			if (index < 0) {
				// KRKN-72
				throw new ScopeException(
						"invalid rawMatrix mapping: missing column for Axis '" + m.getAxis().getName() + "'");
			}
			int type = rawMatrix.getColTypes().get(index);
			m.setMetadata(index, type);
			this.add(m.getData());
			propertyToAlias.put(m.getAxis(), m.getPiece().getAlias());
			propertyToType.put(m.getAxis(), type);
			//
			axesIndirection[i] = m.getIndex();
			i++;
		}

		dataIndirection = new int[mapper.getMeasureMapping().size()];
		int j = 0;
		for (MeasureMapping m : mapper.getMeasureMapping()) {
			int index = rawMatrix.getColNames().indexOf(m.getPiece().getAlias());
			int type = rawMatrix.getColTypes().get(index);
			m.setMetadata(index, type);
			this.add(m.getMapping());
			propertyToAlias.put(m.getMapping(), m.getPiece().getAlias());
			propertyToType.put(m.getMapping(), type);
			//
			dataIndirection[j] = m.getIndex();
			j++;
		}
		// set rows
		rows = rawMatrix.getRows();

		// (T1057) disabling
		/*
		 * // index possible values for a dimension for (RawRow row :
		 * this.getRows()) { int index = 0; for (AxisMapping m : ax_map) {
		 * Object value =row.getAxisValue(index); if (value!=null)
		 * m.getData().getValues().add(value); index++; } }
		 */
	}

	public int getAxisIndirection(int i) {
		if (this.axesIndirection!=null) {
			return this.axesIndirection[i];
		} else {
			return i;//identity
		}
	}

	public int getDataIndirection(int i) {
		if (this.dataIndirection!=null) {
			return this.dataIndirection[i];
		} else {
			return i;//identity
		}
	}

	/**
	 * return the DimensionMember for the ith axis
	 * 
	 * @param i
	 * @return
	 */
	public Object getAxisValue(int i, RawRow row) {
		if (this.dataIndirection!=null) {
			return row.data[axesIndirection[i]];
		} else {
			return row.data[i];// identity
		}
	}

	public int getRowSize() {
		return this.axes.size()+this.kpis.size();
	}

	public int getAxesSize() {
		return this.axes.size();
	}

	public Object getDataValue(int i, RawRow row) {
		if (this.dataIndirection!=null) {
			return row.data[dataIndirection[i]];
		} else {
			return row.data[axes.size()+i];//identity
		}
	}

	public Object getValue(int i, RawRow row) {
		if (i < axes.size()) {
			return getAxisValue(i, row);
		} else {
			return getDataValue(i - axes.size(), row);
		}
	}

	public int getDataSize() {
		return this.kpis.size();
	}

	public Database getDatabase() {
		return this.database;
	}

	public boolean isFromCache() {
		return fromCache;
	}

	public void setFromCache(boolean fromCache) {
		this.fromCache = fromCache;
	}

	public Date getExecutionDate() {
		return executionDate;
	}

	public void setExecutionDate(Date executionDate) {
		this.executionDate = executionDate;
	}

	public boolean isFullset() {
		return fullset;
	}

	public void setFullset(boolean fullset) {
		this.fullset = fullset;
	}

	public boolean add(Measure data) {
		return kpis.add(data);
	}

	public boolean add(AxisValues data) {
		return axes.add(data);
	}

	public void pushRow(RawRow r) {
		rows.add(r);
	}

	public List<Measure> getKPIs() {
		return kpis;
	}

	public List<AxisValues> getAxes() {
		return axes;
	}

	public AxisValues find(Axis axis) {
		for (AxisValues av : axes) {
			if (av.getAxis().equals(axis)) {
				return av;
			}
		}
		// else
		return null;
	}

	public AxisValues getAxisColumn(Axis axis) {
		for (AxisValues ax : axes) {
			if (ax.getAxis().equals(axis)) {
				return ax;
			}
		}
		// else
		return null;
	}

	/**
	 * return the values for the given axis as stored in the matrix (we used to
	 * store those values in a array, but since we are hardly using that
	 * information better to compute it when needed only)
	 * 
	 * @param axis
	 * @return
	 * @throws ComputingException
	 * @throws InterruptedException
	 */
	public Collection<DimensionMember> getAxisValues(Axis axis) throws ComputingException, InterruptedException {
		AxisValues ax = getAxisColumn(axis);
		if (ax != null) {
			// compute values
			int pos = axes.indexOf(ax);
			if (pos >= 0) {
				HashSet<Object> objects = new HashSet<>();
				ArrayList<DimensionMember> members = new ArrayList<>();
				DimensionIndex index = axis.getIndex();
				for (RawRow row : rows) {
					Object value = getAxisValue(pos, row);
					if (!objects.contains(value)) {
						objects.add(value);
						DimensionMember member = index.getMemberByID(value);
						members.add(member);
					}
				}
				return members;
			}
		}
		// else
		return Collections.emptyList();
	}

	/**
	 * orderBy this matrix
	 * 
	 * @param orderBy
	 */
	public void orderBy(List<OrderBy> orderBy) {
		final List<Integer> ordering = new ArrayList<>();
		final List<ORDERING> direction = new ArrayList<>();
		for (OrderBy item : orderBy) {
			ExpressionAST itemExpr = item.getExpression();
			int pos = 0;
			boolean check = false;
			for (AxisValues axis : this.axes) {
				if (axis.getAxis().getReference().equals(itemExpr)) {
					ordering.add(pos);
					direction.add(item.getOrdering());
					check = true;
					pos++;
					break;
				}
				pos++;
			}
			if (!check) {
				// try the kpis
				for (Measure kpi : getKPIs()) {
					if (kpi.getReference().equals(itemExpr)) {
						ordering.add(pos);
						direction.add(item.getOrdering());
						check = true;
						break;
					}
					pos++;
				}
			}
		}
		final int size = ordering.size();
		Collections.sort(rows, new Comparator<RawRow>() {
			@Override
			public int compare(RawRow o1, RawRow o2) {
				for (int i = 0; i < size; i++) {
					Object v1 = getValue(ordering.get(i), o1);
					Object v2 = getValue(ordering.get(i), o2);
					int cc = compareValues(v1, v2);
					if (direction.get(i) == ORDERING.DESCENT)
						cc = -cc;// reverse
					if (cc != 0)
						return cc;
				}
				// equal !
				return 0;
			}
		});
	}

	private int compareValues(Object v1, Object v2) {
		if (v1 == null && v2 != null)
			return -1;
		if (v1 != null && v2 == null)
			return 1;
		if (v1 == null && v2 == null)
			return 0;
		if ((v1 instanceof Comparable) && (v2 instanceof Comparable)) {
			@SuppressWarnings({ "rawtypes", "unchecked" })
			int cc = ((Comparable) v1).compareTo(((Comparable) v2));
			return cc;
		} else {
			int cc = v1.toString().compareTo(v2.toString());
			return cc;
		}
	}

	public void truncate(long limitValue, long offsetValue) {
		int from = (int) Math.max(0, offsetValue);
		int to = (int) Math.min(rows.size(), from + limitValue);
		this.rows = this.rows.subList(from, to);// this is not a copy, just a
												// view
	}

	/**
	 * merge two matrix with different KPIs but must be on the same space
	 * 
	 * @param that
	 * @return
	 * @throws ScopeException
	 */
	public DataMatrix merge(DataMatrix that) throws ScopeException {
		Merger merger = new Merger(this, that);
		return merger.merge(true);
	}

	public DataMatrix filter(DashboardSelection selection) {
		return filter(selection, true);
	}

	/**
	 * filter the matrix given a selection to contain only the required rows
	 * <br>
	 * note: KPIs are not aggregated, it is not performing a roll-up
	 * 
	 * @param selection
	 * @return
	 */
	public DataMatrix filter(DashboardSelection selection, boolean nullIsValid) {
		// first issue: the meta-data are not set
		if (getRows().isEmpty()) {
			return this;// nothing left to filter
		}
		Collection<ApplyFilterCondition> automaton = new ArrayList<ApplyFilterCondition>();
		// first compute the matching automaton based on the selection
		// note that we don't know if the selection dimension exists already in
		// the matrix; if not, just ignore it?
		for (DomainSelection domain : selection.get()) {
			for (Axis axis : domain.getFilters()) {
				// check if the axis is defined
				// if (axis.getDimension().getType()==Type.CATEGORICAL) { //
				// krkn-75, apply that damn filter please!
				AxisValues axisData = getAxisColumn(axis);
				if (axisData != null) {
					// check the axis index
					int index = getAxes().indexOf(axisData);
					ApplyFilterCondition item = null;
					// ok, populate the automaton with filter values
					for (DimensionMember member : domain.getMembers(axis)) {
						if (item == null) {
							item = new ApplyFilterCondition(index, nullIsValid);
							automaton.add(item);
						}
						item.add(member);
					}
				}
				// }
			}
		}
		// ok, we are ready to iterate through the matrix now...
		if (automaton.isEmpty()) {
			return this;
		} else {
			DataMatrix result = new DataMatrix(this);

			for (RawRow row : getRows()) {
				boolean filter = checkRowAND(row, automaton);
				if (filter) {
					result.pushRow(row);
				}
			}
			return result;
		}
	}

	/**
	 * Wrap a filter condition and apply it to a row
	 * 
	 * @author sergefantino
	 *
	 */
	public class ApplyFilterCondition {

		public int index;
		public HashSet<Object> items = new HashSet<Object>();
		private boolean nullIsValid;

		public ApplyFilterCondition(int index, boolean nullIsValid) {
			this.index = index;
			this.nullIsValid = nullIsValid;
		}

		public boolean filter(RawRow row) {
			// DimensionMember m = DataMatrix.this.getDimensionMember(row,
			// index);//row.getAxisValue(DataMatrix.this,index);
			Object m = getAxisValue(index, row);
			return (m == null && this.nullIsValid) || (m != null && items.contains(m));
		}

		public void add(DimensionMember filter) {
			items.add(filter.getID());
		}

		public boolean isEmpty() {
			return items.isEmpty();
		}

	}

	public DataMatrix filter(FilterFunction func) {
		DataMatrix result = new DataMatrix(this);
		for (RawRow row : getRows()) {
			boolean filter = func.check(row);
			if (filter) {
				result.pushRow(row);
			}
		}
		return result;
	}

	/**
	 * simple interface to filter a DataMatrix
	 * 
	 * @author sfantino
	 *
	 */
	public interface FilterFunction {

		public boolean check(RawRow row);

	}

	/**
	 * filter the matrix given a selection to contain only the required rows
	 * <br>
	 * note: KPIs are not aggregated, it is not performing a roll-up
	 * 
	 * @param selection
	 * @return
	 */
	public DataMatrix filterOR(DashboardSelection selection, boolean nullIsValid) {
		// first issue: the meta-data are not set
		if (getRows().isEmpty()) {
			return this;// nothing left to filter
		}
		Collection<ApplyFilterCondition> automaton = new ArrayList<ApplyFilterCondition>();
		// first compute the matching automaton based on the selection
		// note that we don't know if the selection dimension exists already in
		// the matrix; if not, just ignore it?
		for (DomainSelection space : selection.get()) {
			for (Axis axis : space.getFilters()) {
				if (axis.getDimension().getType() == Type.CATEGORICAL) {
					// check if the axis is defined
					AxisValues axisData = getAxisColumn(axis);
					if (axisData != null) {
						// check the axis index
						int index = getAxes().indexOf(axisData);
						ApplyFilterCondition item = null;
						// ok, populate the automaton with filter values
						for (DimensionMember filter : space.getMembers(axis)) {
							if (item == null) {
								item = new ApplyFilterCondition(index, nullIsValid);
								automaton.add(item);
							}
							item.add((DimensionMember) filter);
						}
					}
				}
			}
		}
		// ok, we are ready to iterate through the matrix now...
		if (automaton.isEmpty()) {
			return this;
		} else {
			DataMatrix result = new DataMatrix(this);
			for (RawRow row : getRows()) {
				boolean filter = checkRowOR(row, automaton);
				if (filter) {
					result.pushRow(row);
				}
			}
			return result;
		}
	}

	private boolean checkRowOR(RawRow row, Collection<ApplyFilterCondition> automaton) {
		int failed = 0;
		for (ApplyFilterCondition item : automaton) {
			if (!item.filter(row)) {
				failed++;
				if ((failed >= 1))
					return false;
			}
		}
		return true;
	}

	private boolean checkRowAND(RawRow row, Collection<ApplyFilterCondition> automaton) {
		boolean filter = true;// challenge it
		for (ApplyFilterCondition item : automaton) {
			if (!item.filter(row)) {
				filter = false;
				break;
			}
		}
		return filter;
	}

	/**
	 * return the data sorted; if the matrix is not sorted, it actually modify
	 * the rows internally once.
	 * 
	 * @return
	 */
	public List<RawRow> sortRows() {
		if (rows != null) {
			if (!isSorted) {
				synchronized (this) {
					if (!isSorted) {
						Collections.sort(rows, new Comparator<RawRow>() {
							@Override
							public int compare(RawRow left, RawRow right) {
								return compareTo(left, right);
							}
						});
						isSorted = true;
					}
				}
			}
			return rows;
		} else {
			return getRows();
		}
	}

	/**
	 * hypothesis: left and right rows belong to this matrix
	 * 
	 * @param left
	 * @param right
	 * @return
	 */
	private int compareTo(RawRow left, RawRow right) {
		if (left == right)
			return 0;
		for (int i = 0; i < getAxesSize(); i++) {
			if (getAxisValue(i, left) == null && getAxisValue(i, right) != null)
				return -1;
			if (getAxisValue(i, left) != null && getAxisValue(i, right) == null)
				return 1;
			if (getAxisValue(i, left) == null && getAxisValue(i, right) == null)
				return 0;
			if ((getAxisValue(i, left) instanceof Comparable) && (getAxisValue(i, right) instanceof Comparable)) {
				@SuppressWarnings({ "unchecked", "rawtypes" })
				int cc = ((Comparable) getAxisValue(i, left)).compareTo(((Comparable) getAxisValue(i, right)));
				if (cc != 0)
					return cc;
			} else {
				int cc = getAxisValue(i, left).toString().compareTo(getAxisValue(i, right).toString());
				if (cc != 0)
					return cc;
			}
		}

		return 0;
	}

	public void dump() throws InterruptedException {
		try {
			dump(100);
		} catch (ComputingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void dump(int sizeLimit) throws ComputingException, InterruptedException {
		// dump
		int count = 0;
		for (RawRow row : this.getRows()) {
			StringBuilder output = new StringBuilder();
			//
			for (int i = 0; i < getAxesSize(); i++) {
				AxisValues m = axes.get(i);
				if (m.isVisible()) {
					Object value = getAxisValue(i, row);
					Axis a = m.getAxis();
					output.append(a.getName());
					output.append("=[");
					output.append(value != null ? value.toString() : "--");
					output.append("];");
				}
			}
			int i = 0;
			for (int k = 0; k < getDataSize(); k++, i++) {
				Measure kpi = kpis.get(k);
				Object value = getDataValue(i, row);

				output.append(kpi.getName()).append("=").append(value != null ? value.toString() : "--").append(";");
			}
			//
			logger.info(output.toString());
			count++;
			if (count == sizeLimit) {
				break;
			}
		}
		logger.info("stopped at line " + count + " out of " + getRows().size());
	}

	public void export() throws ComputingException, InterruptedException {
		// export header
		StringBuilder header = new StringBuilder();
		for (int i = 0; i < axes.size(); i++) {
			AxisValues m = axes.get(i);
			header.append(m.getAxis().getDimension().getName()).append(";");
		}
		for (int i = 0; i < kpis.size(); i++) {
			Measure m = kpis.get(i);
			header.append(m.getName()).append(";");
		}
		logger.info(header.toString());
		// expot data
		for (RawRow row : this.getRows()) {
			StringBuilder output = new StringBuilder();
			//
			for (int i = 0; i < getRowSize(); i++) {
				Object value = getValue(i, row);
				output.append(value != null ? value.toString() : "").append(";");
			}
			//
			logger.info(output.toString());
		}
	}

	public List<RawRow> getRows() {
		return rows;
	}

	/**
	 * convert the DataMatrix in a DataTable format that we can exchange through
	 * the API
	 * 
	 * @param ctx
	 * @return
	 * @throws ComputingException
	 */
	public DataTable toDataTable(AppContext ctx, Integer maxResults, Integer startIndex, boolean replaceNullValues)
			throws ComputingException {
		DataTable table = new DataTable();

		List<AxisValues> axes = this.getAxes();

		List<RawRow> rows = this.getRows();
		List<Measure> kpis = this.getKPIs();

		// export header
		List<Col> header = table.getCols();
		int visible_count = 0;
		for (int i = 0; i < axes.size(); i++) {
			AxisValues m = axes.get(i);
			if (m.isVisible()) {
				visible_count++;
				Dimension dim = m.getAxis().getDimension();
				DataType colType;
				ExtendedType colExtType;
				if (dim != null) {
					colType = getDataType(m.getAxis());
					colExtType = getExtendedType(m.getAxis().getDefinitionSafe());
					assert (colType.equals(DataType.values()[colExtType.getDataType()]));
					Col col = new Col(dim.getId(), m.getAxis().getName(), colExtType, Col.Role.DOMAIN);
					col.setDefinition(m.getAxis().prettyPrint());
					col.setOriginType(m.getAxis().getOriginType());
					header.add(col);
				} else {
					String def = m.getAxis().getDefinitionSafe().prettyPrint();
					String ID = m.getAxis().getId();
					String name = m.getAxis().getName();
					colType = getDataType(m.getAxis());
					colExtType = getExtendedType(m.getAxis().getDefinitionSafe());
					assert (colType.equals(DataType.values()[colExtType.getDataType()]));
					DimensionPK pk = new DimensionPK(m.getAxis().getParent().getDomain().getId(), ID);
					Col col = new Col(pk, name, colExtType, Col.Role.DOMAIN);
					if (def != null)
						col.setDefinition(m.getAxis().prettyPrint());
					col.setOriginType(m.getAxis().getOriginType());
					header.add(col);
				}
			}
		}
		for (int i = 0; i < kpis.size(); i++) {
			Measure m = kpis.get(i);
			Metric metric = m.getMetric();
			ExtendedType type = getExtendedType(m.getDefinitionSafe());
			Col col = new Col(metric != null ? metric.getId() : null, m.getName(), type, Col.Role.DATA);
			col.setDefinition(m.prettyPrint());
			col.setOriginType(m.getOriginType());
			header.add(col);
		}
		// export data
		List<DataTable.Row> tableRows = table.getRows();
		int axes_count = getAxes().size();
		int kpi_count = getKPIs().size();
		if (startIndex == null) {
			startIndex = 0;
		}
		startIndex = Math.max(startIndex, 0);
		if (maxResults == null) {
			maxResults = rows.size();
		}
		maxResults = Math.max(maxResults, 0);
		int endIndex = Math.min(rows.size() - 1, startIndex + maxResults);
		if (startIndex <= endIndex) {
			for (int rowIndex = startIndex; rowIndex <= endIndex; rowIndex++) {
				RawRow row = rows.get(rowIndex);
				Object[] values = new Object[visible_count + getDataSize()];
				int colIdx = 0;
				for (int i = 0; i < axes_count; i++) {
					AxisValues m = axes.get(i);
					if (m.isVisible()) {
						Object value = getAxisValue(i, row);
						if ((value == null) && replaceNullValues) {
							values[colIdx++] = "";
						} else {
							values[colIdx++] = value;
						}
					}
				}
				for (int i = 0; i < kpi_count; i++) {
					Object value = getDataValue(i, row);
					if ((value == null) && replaceNullValues) {
						values[colIdx++] = "";
					} else {
						values[colIdx++] = value;
					}
				}
				//
				tableRows.add(new DataTable.Row(values));
			}
		}
		table.setStartIndex(startIndex);
		table.setTotalSize(rows.size());
		table.setFromCache(isFromCache());
		table.setExecutionDate(getExecutionDate());
		table.setFullset(this.fullset);

		return table;
	}

	private DataType getDataType(Axis axis) {
		ExpressionAST expr = axis.getDefinitionSafe();
		IDomain image = expr.getImageDomain();
		if (image.isInstanceOf(IDomain.DATE)) {
			return DataType.DATE;
		} else if (image.isInstanceOf(IDomain.TIMESTAMP)) {
			return DataType.DATE;
		} else if (image.isInstanceOf(IDomain.NUMERIC)) {
			return DataType.NUMBER;
		} else if (image.isInstanceOf(IDomain.STRING)) {
			return DataType.STRING;
		} else {
			return DataType.STRING;
		}
	}

	private ExtendedType getExtendedType(ExpressionAST expr) {
		return expr.computeType(this.database.getSkin());
	}

	public Map<Property, String> getPropertyToAlias() {
		return this.propertyToAlias;

	}

	public Map<Property, Integer> getPropertyToInteger() {
		return this.propertyToType;
	}

	@Override
	public String toString() {
		StringBuilder dump = new StringBuilder();
		dump.append("DataMatrix: size=" + (axes.size() + kpis.size()) + "x" + rows.size()
				+ (fullset ? "(full)" : "(partial)"));
		if (rows != null) {
			int i = 0;
			for (RawRow row : rows) {
				dump.append("\n" + row.toString());
				if (i++ > 10) {
					dump.append("\n(...)");
					break;
				}
			}
		}
		return dump.toString();
	}
}
