/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.pihmalawi.data;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Cohort;
import org.openmrs.Location;
import org.openmrs.api.PatientService;
import org.openmrs.module.pihmalawi.alert.AlertDefinition;
import org.openmrs.module.pihmalawi.alert.AlertEngine;
import org.openmrs.module.pihmalawi.common.JsonObject;
import org.openmrs.module.pihmalawi.metadata.ChronicCareMetadata;
import org.openmrs.module.pihmalawi.metadata.HivMetadata;
import org.openmrs.module.pihmalawi.reporting.library.BaseCohortDefinitionLibrary;
import org.openmrs.module.pihmalawi.reporting.library.BasePatientDataLibrary;
import org.openmrs.module.pihmalawi.reporting.library.ChronicCarePatientDataLibrary;
import org.openmrs.module.pihmalawi.reporting.library.DataFactory;
import org.openmrs.module.pihmalawi.reporting.library.HivPatientDataLibrary;
import org.openmrs.module.reporting.ReportingConstants;
import org.openmrs.module.reporting.cohort.PatientIdSet;
import org.openmrs.module.reporting.cohort.definition.CohortDefinition;
import org.openmrs.module.reporting.cohort.definition.service.CohortDefinitionService;
import org.openmrs.module.reporting.common.DateUtil;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.module.reporting.data.patient.definition.PatientDataDefinition;
import org.openmrs.module.reporting.data.patient.library.BuiltInPatientDataLibrary;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetColumn;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.dataset.definition.DataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.PatientDataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.service.DataSetDefinitionService;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides a foundational class to retain in-memory data for use by the system
 */
public abstract class LivePatientDataSet {

    private final static Log log = LogFactory.getLog(LivePatientDataSet.class);

    @Autowired
    PatientService patientService;

    @Autowired
    DataSetDefinitionService dataSetService;

    @Autowired
    CohortDefinitionService cohortDefinitionService;

    @Autowired
    BaseCohortDefinitionLibrary baseCohorts;

    @Autowired
    BuiltInPatientDataLibrary builtInPatientData;

    @Autowired
    BasePatientDataLibrary basePatientData;

    @Autowired
    HivPatientDataLibrary hivPatientData;

    @Autowired
    ChronicCarePatientDataLibrary ccPatientData;

    @Autowired
    HivMetadata hivMetadata;

    @Autowired
    ChronicCareMetadata ccMetadata;

    @Autowired
    DataFactory df;

    //***** CONSTANTS *****

    public static final String INTERNAL_ID = "internal_id";

    //***** INSTANCE VARIABLES *****

    private LivePatientDataCache cache;
    private AlertEngine alertEngine = new AlertEngine();

    //***** ABSTRACT METHODS *****

    /**
     * @return the DataSetDefinition for this data
     */
    public abstract DataSetDefinition getDataSetDefinition();

    /**
     * @return the AlertDefinitions for this data
     */
    public abstract List<AlertDefinition> getAlertDefinitions();

    //***** PUBLIC METHODS *****

    /**
     * @return the data for a given Patient on a given date and location
     * If date is null, then the current date will be used
     * Location is generally not applicable to the patient data returned, though may be used to determine which data to favor (eg. patient identifiers)
     */
    public JsonObject getDataForPatient(Integer patientId, Date effectiveDate, Location location) {
        Cohort c = new Cohort();
        c.addMember(patientId);
        return getDataForCohort(c, effectiveDate, location).get(patientId);
    }

    /**
     * @return the data for a given cohort on a given date and location
     * If date is null, then the current date will be used
     * Location is generally not applicable to the patient data returned, though may be used to determine which data to favor (eg. patient identifiers)
     */
    public Map<Integer, JsonObject> getDataForCohort(CohortDefinition cohortDefinition, Date effectiveDate, Location location) {
        Cohort cohort = evaluateCohort(cohortDefinition, effectiveDate, location);
        return getDataForCohort(cohort, effectiveDate, location);
    }

    /**
     * @return the data for a given cohort on a given date and location
     * If date is null, then the current date will be used
     * Location is generally not applicable to the patient data returned, though may be used to determine which data to favor (eg. patient identifiers)
     */
    public Map<Integer, JsonObject> getDataForCohort(Cohort cohort, Date effectiveDate, Location location) {

        Map<Integer, JsonObject> data = new HashMap<Integer, JsonObject>();

        log.debug("Getting Data for Cohort size " + cohort.getSize());
        log.debug("Effective Date: " + effectiveDate);
        log.debug("Location: " + location);

        Map<Integer, JsonObject> cachedData = getCache().getDataCache(effectiveDate, location);

        Cohort notCached = PatientIdSet.subtract(new Cohort(cohort.getMemberIds()), new Cohort(cachedData.keySet()));
        log.debug(notCached.size() + " elements of the cohort are not cached, generating these");

        if (notCached.size() > 0) {
            DataSet ds = evaluateDataSet(getDataSetDefinition(), effectiveDate, location, notCached);
            for (DataSetRow row : ds) {
                JsonObject patientData = new JsonObject();
                patientData.put("today", effectiveDate);
                patientData.put("location", (location != null ? location.getUuid() : null));
                for (DataSetColumn c : row.getColumnValues().keySet()) {
                    if (patientData.containsKey(c.getName())) {
                        throw new RuntimeException("Duplicate column " + c.getName() + " found.  Please change column name in data set");
                    }
                    patientData.put(c.getName(), row.getColumnValues().get(c));
                }
                List<AlertDefinition> matchingAlerts = alertEngine.evaluateMatchingAlerts(getAlertDefinitions(), patientData);
                Set<String> alerts = new HashSet<String>();
                for (AlertDefinition ad : matchingAlerts) {
                    alerts.add(ad.getName());
                }
                patientData.put("alerts", alerts);
                Integer internalId = (Integer) row.getColumnValue(INTERNAL_ID);
                if (internalId == null) {
                    throw new RuntimeException("No " + INTERNAL_ID + " found for data set row: " + row);
                }
                data.put(internalId, patientData);
            }
            log.debug("Number of results generated " + data.size());

            getCache().updateCache(data, effectiveDate, location);
        }
        for (Integer pId : cohort.getMemberIds()) {
            data.put(pId, cachedData.get(pId));
        }

        return data;
    }

    //***** HELPER METHODS *****

    /**
     * Utility/convenience method to map straight through the mappings
      */
    protected void addColumn(PatientDataSetDefinition dsd, String columnName, PatientDataDefinition pdd) {
        dsd.addColumn(columnName, pdd, Mapped.straightThroughMappings(pdd));
    }

    /**
     * Helper method to evaluate a Cohort Definition
     */
    protected Cohort evaluateCohort(CohortDefinition cd, Date endDate, Location location) {
        EvaluationContext context = createEvaluationContext(endDate, location, null);
        try {
            return cohortDefinitionService.evaluate(cd, context);
        }
        catch (EvaluationException e) {
            throw new RuntimeException("Unable to evaluate cohort", e);
        }
    }

    /**
     * Helper method to evaluate a Data Set Definition
     */
    protected DataSet evaluateDataSet(DataSetDefinition dsd, Date endDate, Location location, Cohort baseCohort) {
        EvaluationContext context = createEvaluationContext(endDate, location, baseCohort);
        try {
            log.warn("Evaluating data set for " + baseCohort.size() + " patients at " + (location == null ? "all locations" : location.getName()) + " on " + DateUtil.formatDate(endDate, "yyyy-MM-dd"));
            return dataSetService.evaluate(dsd, context);
        }
        catch (EvaluationException e) {
            throw new RuntimeException("Unable to evaluate data set", e);
        }
    }

    /**
     * Helper method to construct an Evaluation Context
     */
    protected EvaluationContext createEvaluationContext(Date endDate, Location location, Cohort baseCohort) {
        EvaluationContext context = new EvaluationContext();
        context.addParameterValue(ReportingConstants.END_DATE_PARAMETER.getName(), ObjectUtil.nvl(endDate, new Date()));
        context.addParameterValue(ReportingConstants.LOCATION_PARAMETER.getName(), location);
        context.setBaseCohort(baseCohort);
        return context;
    }

    /**
     * @return Cache of patient data
     */
    public LivePatientDataCache getCache() {
        if (cache == null) {
            cache = new LivePatientDataCache();
        }
        return cache;
    }
}
