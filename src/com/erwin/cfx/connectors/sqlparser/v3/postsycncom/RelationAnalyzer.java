/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.erwin.cfx.connectors.sqlparser.v3.postsycncom;

import com.ads.api.beans.kv.KeyValue;
import com.ads.api.beans.mm.MappingSpecificationRow;
import com.ads.api.util.KeyValueUtil;
import com.erwin.dataflow.model.xml.dataflow;
import com.erwin.dataflow.model.xml.relation;
import com.erwin.dataflow.model.xml.sourceColumn;
import com.erwin.dataflow.model.xml.table;
import com.erwin.dataflow.model.xml.targetColumn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author Balaji
 */
public class RelationAnalyzer {

    public static final String DELIMITER = "#@ERWIN@#";
    private LinkedHashMap<String, String> tableAliasMap;
    private LinkedHashMap<String, String> businessRuleMap;
    private LinkedHashMap<String, HashSet<String>> keyValuesMap;
    private LinkedHashMap<String, String> joinComponentMap;
    public static HashMap<String, String> sourceAndTargetDatabaseAndServerDetails = new HashMap<>();

    public ArrayList<MappingSpecificationRow> analyzeRelations(dataflow dtflow, String[] sysEnvDetails, Map<String, String> tableSystemEnvMap, Map<String, String[]> metadataTableColumnDetailsMap, String databaseName, String serverName, String defSchemaName, String postSyncup, KeyValueUtil kvUtil, HashMap udf) {
        tableAliasMap = new LinkedHashMap<>();
        businessRuleMap = new LinkedHashMap<>();
        keyValuesMap = new LinkedHashMap<>();
        joinComponentMap = new LinkedHashMap<>();
        sourceAndTargetDatabaseAndServerDetails = new HashMap<>();
        
        List<table> tables = dtflow.getTables();
        List<table> resultsets = dtflow.getResultsets();
        List<relation> relations = dtflow.getRelations();
        if (relations == null) {
            return new ArrayList<>();
        }
        prepareAliasMap(tables);
        prepareAliasMap(resultsets);

        LinkedHashMap<String, HashSet<String>> srcTgtCmpnt = new LinkedHashMap<>();
        for (relation relation : relations) {
            prepareBusinessRules(relation);
            prepareKeyValues(relation);
            prepareSrcTgtMap(relation, srcTgtCmpnt);
        }

        preparejoinComponent(srcTgtCmpnt);
        businessRuleMap = ModifyBusinessRuleMap();

        ArrayList<MappingSpecificationRow> specRows = new ArrayList<>();
        for (relation relation : relations) {
            if (!relation.getType().equals("lineage") || relation.getTarget() == null
                    || relation.getSources() == null) {
                continue;
            }
            List<sourceColumn> sources = relation.getSources();
            targetColumn target = relation.getTarget();
            String tgtTableName = target.getParent_name();
            String tgtColumnName = target.getColumn();
            String srcTableName = "";
            String srcColumnName = "";
            HashSet<String> duplicateSources = new HashSet<>();
            String[] srcColumnDetails = {"", "0", "0", "0"};
            String[] tgtColumnDetails = {"", "0", "0", "0"};
            boolean srcSysEnvUpdateFlag = false;
            String[] updatedSysEnvDetails = Arrays.copyOf(sysEnvDetails, sysEnvDetails.length);

            for (sourceColumn source : sources) {
                if (duplicateSources.contains(source.getParent_name() + "." + source.getColumn())) {
                    continue;
                } else {
                    duplicateSources.add(source.getParent_name() + "." + source.getColumn());
                }
                String tableName = source.getParent_name();
                if (joinComponentMap.get(tableName) != null) {
                    tableName = joinComponentMap.get(tableName);
                }
                srcTableName = "".equals(srcTableName) ? tableName
                        : srcTableName + "\n" + tableName;
                srcColumnName = "".equals(srcColumnName) ? source.getColumn()
                        : srcColumnName + "\n" + source.getColumn();
            }
            if (joinComponentMap.get(tgtTableName) != null) {
                tgtTableName = joinComponentMap.get(tgtTableName);
            }
            String key = tgtTableName + DELIMITER + tgtColumnName;
            String bRule = businessRuleMap.get(key);
            if (bRule != null) {
                businessRuleMap.remove(key);
            }

            /*source system name, target system name, source env name, target env name, scale, precision, data type*/
            srcTableName = cleanTableName(srcTableName);
            srcColumnName = cleanColumnName(srcColumnName);
            tgtTableName = cleanTableName(tgtTableName);
            tgtColumnName = cleanColumnName(tgtColumnName);

            String[] srcTgtDetails = {srcTableName, srcColumnName, tgtTableName, tgtColumnName, bRule};
//            specRows.add(addSpecRow(srcTgtDetails, srcColumnDetails, tgtColumnDetails, updatedSysEnvDetails));
            specRows.add(addSpecRow(srcTgtDetails, srcColumnDetails, tgtColumnDetails, updatedSysEnvDetails, databaseName, serverName, defSchemaName, postSyncup, kvUtil, udf));
        }
        if (businessRuleMap.size() > 0) {
            ArrayList<MappingSpecificationRow> brSpecRows = new ArrayList<>();
            for (MappingSpecificationRow specRow : specRows) {
                String srcTableName = specRow.getSourceTableName();
                String srcColumnName = specRow.getSourceColumnName();
                String bRule = null;
                bRule = businessRuleMap.get(srcTableName + DELIMITER + srcColumnName);
                if (bRule == null && srcTableName.split("\n").length > 1) {
                    for (int i = 0; i < srcTableName.split("\n").length; i++) {
                        String tablename = "";
                        String columnName = "";
                        if (srcColumnName.split("\n").length == srcTableName.split("\n").length) {
                            tablename = srcTableName.split("\n")[i];
                            columnName = srcColumnName.split("\n")[i];
                        }
                        bRule = businessRuleMap.get(tablename + DELIMITER + columnName);
                        if (bRule != null) {
                            srcTableName = tablename;
                            srcColumnName = columnName;
                            break;
                        }
                    }
                }
                if (bRule != null) {
                    String srcTableNameCleaned = cleanTableName(srcTableName);
                    String srcColumnNameCleaned = cleanColumnName(srcColumnName);

                    MappingSpecificationRow specRow1 = new MappingSpecificationRow();
                    setSpecification(specRow1, sysEnvDetails);
                    specRow1.setTargetTableName(srcTableNameCleaned);
                    specRow1.setTargetColumnName(srcColumnNameCleaned);
                    specRow1.setBusinessRule(bRule);
                    brSpecRows.add(specRow1);
                    businessRuleMap.remove(srcTableName + DELIMITER + srcColumnName);
                    if (businessRuleMap.isEmpty()) {
                        break;
                    }
                }
            }
            specRows.addAll(brSpecRows);
        }
        if (businessRuleMap.size() > 0) {
            for (String key : businessRuleMap.keySet()) {
                String srcTableName = key.split(DELIMITER)[0];
                String srcColumnName = key.split(DELIMITER)[1];
                String bRule = businessRuleMap.get(key);

                String srcTableNameCleaned = cleanTableName(srcTableName);
                String srcColumnNameCleaned = cleanColumnName(srcColumnName);

                MappingSpecificationRow specRow1 = new MappingSpecificationRow();
                setSpecification(specRow1, sysEnvDetails);
                specRow1.setTargetTableName(srcTableNameCleaned);
                specRow1.setTargetColumnName(srcColumnNameCleaned);
                specRow1.setBusinessRule(bRule);
                specRows.add(specRow1);
            }
        }
        //For Madhus Requirement
        //specRows = RemoveResultsetComponent(specRows);
        return specRows;
    }

    private MappingSpecificationRow addSpecRow(String[] srcTgtDetails, String[] sysEnvDetails) {
        MappingSpecificationRow mSpecRow = new MappingSpecificationRow();
        setSpecification(mSpecRow, sysEnvDetails);
        mSpecRow.setSourceTableName(srcTgtDetails[0]);
        mSpecRow.setSourceColumnName(srcTgtDetails[1]);
        mSpecRow.setTargetTableName(srcTgtDetails[2]);
        mSpecRow.setTargetColumnName(srcTgtDetails[3]);

        String bRule = srcTgtDetails[4];
        if (bRule != null) {
            mSpecRow.setBusinessRule(bRule);
        }
        return mSpecRow;
    }

    private MappingSpecificationRow addSpecRow(String[] srcTgtDetails, String[] srcColumnDetails, String[] tgtColumnDetails, String[] sysEnvDetails, String databaseName, String serverName, String defSchemaName, String postSyncup, KeyValueUtil kvUtil, HashMap udf) {
        MappingSpecificationRow mSpecRow = new MappingSpecificationRow();
        setSpecification(mSpecRow, sysEnvDetails);

        String sourceTable = srcTgtDetails[0];
        String targetTable = srcTgtDetails[2];

//        mSpecRow.setSourceTableName(sourceTable);
        mSpecRow.setSourceColumnName(srcTgtDetails[1]);
        mSpecRow.setSourceColumnDatatype(srcColumnDetails[0]);
        mSpecRow.setSourceColumnLength(Integer.parseInt(srcColumnDetails[1]));
        mSpecRow.setSourceColumnScale(Integer.parseInt(srcColumnDetails[2]));
        mSpecRow.setSourceColumnPrecision(Integer.parseInt(srcColumnDetails[3]));

//        mSpecRow.setTargetTableName(targetTable);
        mSpecRow.setTargetColumnName(srcTgtDetails[3]);
        mSpecRow.setTargetColumnDatatype(tgtColumnDetails[0]);
        mSpecRow.setTargetColumnLength(Integer.parseInt(tgtColumnDetails[1]));
        mSpecRow.setTargetColumnScale(Integer.parseInt(tgtColumnDetails[2]));
        mSpecRow.setTargetColumnPrecision(Integer.parseInt(tgtColumnDetails[3]));

        String bRule = srcTgtDetails[4];
        if (bRule != null) {
            mSpecRow.setBusinessRule(bRule);
        }
        setSourceTableAndUDF1AndTargetTableAndUDF2(sourceTable, databaseName, serverName, mSpecRow, targetTable, defSchemaName, postSyncup, kvUtil, udf);
//        sequenceIdCount++;
        return mSpecRow;
    }

    public void setSourceTableAndUDF1AndTargetTableAndUDF2(String sourceTableName, String ssisdatabaseName, String ssisserverName, MappingSpecificationRow mSpecRow, String targetTableName, String defSchemaName, String postSyncup, KeyValueUtil kvUtil, HashMap udf) {

        ArrayList<String> tableList = null;
        String targetDatabaseName = "";
        String targetServerName = "";
        String sourceDetials = "";
        String targetDetials = "";
        String sourceDatabaseName = "";
        String sourceServerName = "";

        ArrayList<String> sourceTableList = new ArrayList<>();
        ArrayList<String> tagetTableList = new ArrayList<>();
        ArrayList<String> sourceDatabaseList = new ArrayList<>();
        ArrayList<String> targetDatabaseList = new ArrayList<>();
        ArrayList<String> sourceServerList = new ArrayList<>();
        ArrayList<String> targetServerList = new ArrayList<>();

        List<KeyValue> keyValueList = new ArrayList<>();

        tableList = getTableNameAlongWithServerAndDatabaseName(sourceTableName, ssisdatabaseName, ssisserverName, defSchemaName);

        sourceDetials = StringUtils.join(tableList, "\n");

        String[] spiltSourceDetails = sourceDetials.split("\n");

        for (String sourceDatilsIndividual : spiltSourceDetails) {

            sourceTableName = "";
            sourceDatabaseName = "";
            sourceServerName = "";

            int sourceLength = 0;
            sourceLength = sourceDatilsIndividual.split("###").length;
            switch (sourceLength) {
                case 3:
                    sourceTableName = sourceDatilsIndividual.split("###")[0];
                    sourceDatabaseName = sourceDatilsIndividual.split("###")[1];
                    sourceServerName = sourceDatilsIndividual.split("###")[2];
                    break;
                case 2:
                    sourceTableName = sourceDatilsIndividual.split("###")[0];
                    sourceDatabaseName = sourceDatilsIndividual.split("###")[1];
                    break;
                case 1:
                    sourceTableName = sourceDatilsIndividual.split("###")[0];
                    break;
                default:
                    break;
            }

            sourceTableList.add(sourceTableName);
            sourceDatabaseList.add(sourceDatabaseName);
            sourceServerList.add(sourceServerName);

        }

        tableList = getTableNameAlongWithServerAndDatabaseName(targetTableName, ssisdatabaseName, ssisserverName, defSchemaName);

        targetDetials = StringUtils.join(tableList, "\n");

        String[] spiltTargetDetials = targetDetials.split("\n");

        for (String targetDatilsIndividual : spiltTargetDetials) {

            targetTableName = "";
            targetDatabaseName = "";
            targetServerName = "";

            int targetLength = 0;
            targetLength = targetDatilsIndividual.split("###").length;
            switch (targetLength) {
                case 3:
                    targetTableName = targetDetials.split("###")[0];
                    targetDatabaseName = targetDetials.split("###")[1];
                    targetServerName = targetDetials.split("###")[2];
                    break;
                case 2:
                    targetTableName = targetDetials.split("###")[0];
                    targetDatabaseName = targetDetials.split("###")[1];
                    break;
                case 1:
                    targetTableName = targetDetials.split("###")[0];
                    break;
                default:
                    break;
            }

            tagetTableList.add(targetTableName);
            targetDatabaseList.add(targetDatabaseName);
            targetServerList.add(targetServerName);

        }

        String stringSourceTableList = StringUtils.join(sourceTableList, "\n");
        String stringSourceDatabaseList = StringUtils.join(sourceDatabaseList, "\n");
        String stringSourceServerList = StringUtils.join(sourceServerList, "\n");

        String stringTargetTableList = StringUtils.join(tagetTableList, "\n");
        String stringTargetDatabaseList = StringUtils.join(targetDatabaseList, "\n");
        String stringTargetServerList = StringUtils.join(targetServerList, "\n");

        if (postSyncup.equalsIgnoreCase("udf")) {
//            HashMap udf = MappingCreator.userDefined;
            mSpecRow = UserDefinedField.setData(mSpecRow, Integer.parseInt(udf.get("srcServer").toString()), stringSourceServerList);
            mSpecRow = UserDefinedField.setData(mSpecRow, Integer.parseInt(udf.get("srcDB").toString()), stringSourceDatabaseList);
            mSpecRow = UserDefinedField.setData(mSpecRow, Integer.parseInt(udf.get("tgtServer").toString()), stringTargetServerList);
            mSpecRow = UserDefinedField.setData(mSpecRow, Integer.parseInt(udf.get("tgtDB").toString()), stringTargetDatabaseList);
//            mSpecRow.setUserDefined1(stringSourceServerList);
//            mSpecRow.setUserDefined2(stringSourceDatabaseList);
//            mSpecRow.setUserDefined3(stringTargetServerList);
//            mSpecRow.setUserDefined4(stringTargetDatabaseList);

        }
        mSpecRow.setSourceTableName(stringSourceTableList);

        mSpecRow.setTargetTableName(stringTargetTableList);

        String separator = "@ERWINSEPARATOR@";

        String key = stringSourceTableList + separator + stringTargetTableList;
        String sourceVaule = stringSourceDatabaseList + separator
                + stringSourceServerList;
        String targetValue = stringTargetDatabaseList + separator + stringTargetServerList;

        sourceAndTargetDatabaseAndServerDetails.put(stringSourceTableList, sourceVaule);
        sourceAndTargetDatabaseAndServerDetails.put(stringTargetTableList, targetValue);

    }

    public ArrayList<String> getTableNameAlongWithServerAndDatabaseName(String tableName, String ssisdatabaseName, String ssisserverName, String defSchemaName) {

        String queryServerName = "";
        String queryDatabaseName = "";
        String querySchemaName = "";
        String queryTableName = "";

        ArrayList<String> tableAndServerAndDatabaseList = new ArrayList<>();

        if (!StringUtils.isBlank(tableName)) {

            ArrayList<String> TableNameDetailedList = SyncMetadataJsonFileDesign.getTableName(tableName, "");
            for (String sourceDetailedTableName : TableNameDetailedList) {
                try {
                    queryServerName = sourceDetailedTableName.split("erwinseprator")[0];
                    queryDatabaseName = sourceDetailedTableName.split("erwinseprator")[1];
                    querySchemaName = sourceDetailedTableName.split("erwinseprator")[2];
                    queryTableName = sourceDetailedTableName.split("erwinseprator")[3];

                    if (queryDatabaseName.equals("")) {
                        String useLineDataBaseName = "";
                        if (ssisdatabaseName.contains("separatorUseLine")) {

                            try {
                                useLineDataBaseName = ssisdatabaseName.split("separatorUseLine")[1];
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            if (!StringUtils.isBlank(useLineDataBaseName)) {

                                if (!(queryTableName.contains("RESULT_OF_") || queryTableName.contains("INSERT-SELECT") || queryTableName.contains("UPDATE-") || queryTableName.contains("RS_") || queryTableName.contains("MERGE") || queryTableName.contains("UNION"))) {
                                    queryDatabaseName = useLineDataBaseName;
                                }

                            }
                        } else {
                            if (!(queryTableName.contains("RESULT_OF_") || queryTableName.contains("INSERT-SELECT") || queryTableName.contains("UPDATE-") || queryTableName.contains("RS_") || queryTableName.contains("MERGE") || queryTableName.contains("UNION"))) {
                                queryDatabaseName = ssisdatabaseName;
                            }

                        }

                    }
                    if (queryServerName.equals("")) {
                        if (!(queryTableName.contains("RESULT_OF_") || queryTableName.contains("INSERT-SELECT") || queryTableName.contains("UPDATE-") || queryTableName.contains("RS_") || queryTableName.contains("MERGE") || queryTableName.contains("UNION"))) {
                            queryServerName = ssisserverName;
                        }

                    }

                } catch (Exception e) {

                }
                if (!StringUtils.isBlank(queryDatabaseName)) {
                    queryDatabaseName = queryDatabaseName.replaceAll("[^a-zA-Z0-9]", "_");
                } else if (!StringUtils.isBlank(queryServerName)) {
                    queryServerName = queryServerName.replaceAll("[^a-zA-Z0-9]", "_");
                }

                queryTableName = getTableNameDefaults(queryTableName, defSchemaName, querySchemaName);
                tableAndServerAndDatabaseList.add(queryTableName + "###" + queryDatabaseName + "###" + queryServerName);
            }

        }
        return tableAndServerAndDatabaseList;
    }

    public static String getTableNameDefaults(String inputTableName, String defaultSchemaName, String querySchemaName) {
        try {
            if (inputTableName.contains("RESULT_OF_") || inputTableName.contains("INSERT-SELECT") || inputTableName.contains("UPDATE-") || inputTableName.contains("RS_") || inputTableName.contains("MERGE") || inputTableName.contains("UNION")) {
                return inputTableName;
            } else if (StringUtils.isBlank(querySchemaName)) {

                inputTableName = defaultSchemaName + "." + inputTableName;
            }
        } catch (Exception e) {

        }
        return inputTableName;
    }

    private void prepareBusinessRules(relation relation) {
        if (!relation.getType().equals("businessrule")) {
            return;
        }
        List<sourceColumn> sources = relation.getSources();
        targetColumn target = relation.getTarget();
        String function = relation.getTarget().getFunction();
        String targetColumn = target.getParent_name() + DELIMITER + target.getColumn();

        if (sources == null || sources.isEmpty()) {
            if (businessRuleMap.get(targetColumn) != null && !businessRuleMap.get(targetColumn).contains(function)) {
                function = businessRuleMap.get(targetColumn) + "\n" + function;
            }
            businessRuleMap.put(targetColumn, function);
            return;
        }
        for (sourceColumn source : sources) {
            String tableAliasName = tableAliasMap.get(source.getParent_id());
            String tableNameWithAlias = tableAliasName != null ? tableAliasName + "." + source.getColumn() : null;
            String replaceWith = source.getParent_name() + "." + source.getColumn();
            if (tableNameWithAlias != null && function.contains(tableNameWithAlias) && !function.contains(replaceWith)) {
                function = function.replace(tableNameWithAlias, replaceWith);
            }
        }
        if (businessRuleMap.get(targetColumn) != null && !businessRuleMap.get(targetColumn).contains(function)) {
            function = businessRuleMap.get(targetColumn) + "\n" + function;
        }
        businessRuleMap.put(targetColumn, function);
    }

    private void prepareAliasMap(List<table> tables) {
        for (table table : tables) {
            tableAliasMap.put(table.getId(), table.getAlias());
        }
    }

    private void prepareKeyValues(relation relation) {
        String[] reqRelations = {"join", "where", "groupBy", "orderBy"};
        List relationsList = Arrays.asList(reqRelations);
        if (!relationsList.contains(relation.getType())) {
            return;
        }
        if (relation.getType().equals("join")) {
            HashSet joinCondSet = keyValuesMap.get("JOIN_CONDITION");
            if (joinCondSet == null) {
                joinCondSet = new HashSet();
            }
            String joinCondition = prepareCondition(relation);
            joinCondition = prepareJoinCondition(relation, joinCondition);
            joinCondSet.add(joinCondition + DELIMITER + capFirstLetter(relation.getJoinType()));
            keyValuesMap.put("JOIN_CONDITION", joinCondSet);
        }
        if (relation.getType().equals("where")) {
            HashSet whereCondSet = keyValuesMap.get("WHERE_CONDITION");
            if (whereCondSet == null) {
                whereCondSet = new HashSet();
            }
            String condition = prepareCondition(relation);
            whereCondSet.add(condition);
            keyValuesMap.put("WHERE_CONDITION", whereCondSet);
        }
        if (relation.getType().equals("groupBy")) {
            HashSet groupbyCondSet = keyValuesMap.get("GROUPBY_CONDITION");
            if (groupbyCondSet == null) {
                groupbyCondSet = new HashSet();
            }
            String condition = prepareCondition(relation);
            groupbyCondSet.add(condition);
            keyValuesMap.put("GROUPBY_CONDITION", groupbyCondSet);
        }
        if (relation.getType().equals("orderBy")) {
            HashSet orderbyCondSet = keyValuesMap.get("ORDERBY_CONDITION");
            if (orderbyCondSet == null) {
                orderbyCondSet = new HashSet();
            }
            String condition = prepareCondition(relation);
            orderbyCondSet.add(condition);
            keyValuesMap.put("ORDERBY_CONDITION", orderbyCondSet);
        }
    }

    private void setSpecification(MappingSpecificationRow specRow, String[] sysEnvDetails) {
        specRow.setSourceSystemName(sysEnvDetails[0]);
        specRow.setSourceSystemEnvironmentName(sysEnvDetails[1]);
        specRow.setTargetSystemName(sysEnvDetails[2]);
        specRow.setTargetSystemEnvironmentName(sysEnvDetails[3]);
    }

    public LinkedHashMap<String, HashSet<String>> getKeyValuesMap() {
        return keyValuesMap;
    }

    private String prepareJoinCondition(relation relation, String condition) {
        targetColumn target = relation.getTarget();
        if (target == null) {
            return condition;
        }
        String tableAliasNames = tableAliasMap.get(target.getParent_id());
        String columnName = target.getColumn();
        boolean flag = false;
        String toBeReplaceWith = target.getParent_name() + "." + columnName;
        if (condition.contains(toBeReplaceWith)) {
            return condition;
        }
        if (tableAliasNames != null) {
            //If multiple alias names with comma separated
            List<String> aliasList = Arrays.asList(tableAliasNames.split(","));
            for (String alias : aliasList) {
                String tableNameWithAlias = alias + "." + columnName;
                if (condition.contains(tableNameWithAlias)) {
                    condition = condition.replace(tableNameWithAlias, toBeReplaceWith);
                    flag = true;
                    break;
                }
            }
        }

//        if (!flag && condition.contains(columnName)) {
//            condition = condition.replace(columnName,
//                    target.getParent_name() + "." + columnName);
//        }
        return condition;
    }

    private String prepareCondition(relation relation) {

        String condition = relation.getCondition();
        List<sourceColumn> sources = relation.getSources();
        if (sources != null) {
            HashSet<String> srcDuplicates = new HashSet<>();
            for (sourceColumn source : sources) {

                String tableAliasNames = tableAliasMap.get(source.getParent_id());
                String columnName = source.getColumn();
                boolean flag = false;
                String toBeReplaceWith = source.getParent_name() + "." + columnName;
                if (srcDuplicates.contains(toBeReplaceWith) || condition.contains(toBeReplaceWith)) {
                    continue;
                }
                if (tableAliasNames != null) {
                    //If multiple alias names with comma separated
                    List<String> aliasList = Arrays.asList(tableAliasNames.split(","));
                    for (String alias : aliasList) {
                        String toBeReplaced = alias + "." + columnName;
                        if (condition.contains(toBeReplaced)) {
                            condition = condition.replace(toBeReplaced, toBeReplaceWith);
                            flag = true;
                            break;
                        }
                    }
                }

//                if (!flag && condition.contains(columnName)) {
//                    condition = condition.replace(columnName,
//                            toBeReplaceWith);
//                }
                srcDuplicates.add(toBeReplaceWith);
            }
        }
        return condition;
    }

    private void preparejoinComponent(LinkedHashMap<String, HashSet<String>> srcTgtCmpnt) {

        HashSet<String> joins = keyValuesMap.get("JOIN_CONDITION");
        if (joins == null) {
            return;
        }

        for (String join : joins) {
            if (join.split("=").length <= 1) {
                continue;
            }
            String source1 = getParentName(join.split("=")[0]);
            String source2 = getParentName(join.split("=")[1]);
            for (String targetTable : srcTgtCmpnt.keySet()) {
                HashSet<String> sources = srcTgtCmpnt.get(targetTable);
                if (sources.contains(source1) && sources.contains(source2)) {
                    joinComponentMap.put(targetTable, "JOIN_" + targetTable);
                    break;
                }
            }
        }
    }

    private String getParentName(String joinCond) {
        if (joinCond.split("\\.").length > 1) {
            joinCond = joinCond.substring(0, joinCond.lastIndexOf("."));
        }
        return joinCond.trim();
    }

    private String capFirstLetter(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private void prepareSrcTgtMap(relation relation, LinkedHashMap<String, HashSet<String>> srcTgtCmpnt) {

        if (!relation.getType().equals("lineage") || relation.getTarget() == null
                || relation.getSources() == null) {
            return;
        }
        String tgtCmpnt = relation.getTarget().getParent_name();
        for (sourceColumn srcColumn : relation.getSources()) {
            String srcCmpnt = srcColumn.getParent_name();
            HashSet srcSet = srcTgtCmpnt.get(tgtCmpnt);
            if (srcSet == null) {
                srcSet = new HashSet();
            }
            srcSet.add(srcCmpnt);
            srcTgtCmpnt.put(tgtCmpnt, srcSet);
        }
    }

    private LinkedHashMap<String, String> ModifyBusinessRuleMap() {
        Set<String> keyset = businessRuleMap.keySet();
        LinkedHashMap<String, String> brRuleMap = new LinkedHashMap<>();
        for (String key : keyset) {
            String tableName = key.split(DELIMITER)[0];
            String columnName = key.split(DELIMITER)[1];
            String value = businessRuleMap.get(key);
            String newCompName = joinComponentMap.get(tableName);
            if (newCompName != null) {
                brRuleMap.put(newCompName + DELIMITER + columnName, value);
            } else {
                brRuleMap.put(key, value);
            }
        }
        return brRuleMap;
    }

    private ArrayList<MappingSpecificationRow> RemoveResultsetComponent(ArrayList<MappingSpecificationRow> specRows) {
        ArrayList<MappingSpecificationRow> specRowList = new ArrayList<>();
        for (MappingSpecificationRow mappingSpecificationRow : specRows) {
            String targetTable = mappingSpecificationRow.getTargetTableName();
            if (!targetTable.startsWith("RS_")) {
                specRowList.add(mappingSpecificationRow);
            }
        }
        return specRowList;
    }

    public static String cleanTableAndColumnNames(String str) {

        str = str.replace("[", "").replace("]", "");
        str = str.replace("`", "");
        str = str.replace("'", "");
        str = str.replace("\"", "");

        return str;
    }

    public static String cleanTableName(String tableOrColumn) {

        try {
            if (tableOrColumn != null && !tableOrColumn.isEmpty()) {

//                tableOrColumn = tableOrColumn.replace("[", "").replace("]", "");
                tableOrColumn = tableOrColumn.replace("`", "");
                tableOrColumn = tableOrColumn.replace("'", "");
                tableOrColumn = tableOrColumn.replace("\"", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return tableOrColumn;
    }

    public static String cleanColumnName(String tableOrColumn) {

        try {
            if (tableOrColumn != null && !tableOrColumn.isEmpty()) {

                tableOrColumn = tableOrColumn.replace("[", "").replace("]", "");
                tableOrColumn = tableOrColumn.replace("`", "");
                tableOrColumn = tableOrColumn.replace("'", "");
                tableOrColumn = tableOrColumn.replace("\"", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return tableOrColumn;
    }
}
