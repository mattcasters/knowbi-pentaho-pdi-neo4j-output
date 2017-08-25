package bi.know.kettle.neo4j.output;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang.StringEscapeUtils;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;



public class Neo4JOutput  extends BaseStep implements StepInterface {
	private static Class<?> PKG = Neo4JOutput.class; // for i18n purposes, needed by Translator2!!

	private Neo4JOutputMeta meta; 
	private Neo4JOutputData data;
	private Transaction tx;
	private int nbRows;
	private String[] fieldNames;
	private Object[] r; 
	private Object[] outputRow;
	private Driver driver; 
	private Session session;
	
    public Neo4JOutput(StepMeta s, StepDataInterface stepDataInterface, int c, TransMeta t, Trans dis) {
		super(s,stepDataInterface,c,t,dis);
	}
    

    /**
	 * TODO:
	 * 1. option to do CREATE/MERGE (merge default?)
	 * 2. optional commit size 
	 * 3. option to return node id?
	 */
    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException{
    	
    	meta = (Neo4JOutputMeta)smi;
    	data =  (Neo4JOutputData)sdi;
    	
    	r = getRow();
    	
        if (first){
            first = false;
            nbRows= 0;
            
	        data.outputRowMeta = (RowMetaInterface)getInputRowMeta().clone();
   	        fieldNames =  data.outputRowMeta.getFieldNames(); 
        }
        
        if(r == null){
        	setOutputDone();
        	return false; 
        }else{
        	try{ 
        		outputRow = RowDataUtil.resizeArray( r, data.outputRowMeta.size());
        		if(meta.getFromNodeProps().length > 0) {
    	   	     	createNode(meta.getFromNodeLabels(), meta.getFromNodeProps(), meta.getFromNodePropNames()); 
        		}
        		if(meta.getToNodeProps().length > 0) {
    	   	     	createNode(meta.getToNodeLabels(), meta.getToNodeProps(), meta.getToNodePropNames()); 
        		}
    			createRelationship();
	   	     	putRow(data.outputRowMeta, outputRow);
	   	     	nbRows++;
	   	     	setLinesWritten(nbRows);
	   	     	setLinesOutput(nbRows);
	   	     	if(nbRows%10000 == 0) {
	   	     		commit();
	   	     	}
        	}catch(Exception e){
        		logError(BaseMessages.getString(PKG, "Neo4JOutput.addNodeError") + e.getMessage());
        	}
        	
        	try{            	
        	}catch(Exception e){
        		logError(BaseMessages.getString(PKG, "Neo4JOutput.addRelationshipError") + e.getMessage());
        	}
        	return true; 
        }
    }
    
    
	public boolean init(StepMetaInterface smi, StepDataInterface sdi){
	    meta = (Neo4JOutputMeta)smi;
	    data = (Neo4JOutputData)sdi;
	    
	    String url = meta.getProtocol() + "://" + meta.getHost() + ":" + meta.getPort();  
	    driver = GraphDatabase.driver(url, AuthTokens.basic(meta.getUsername(), meta.getPassword()));
	    session = driver.session();
	    tx = session.beginTransaction();
	    
	    return super.init(smi, sdi);
	}

	public void dispose(StepMetaInterface smi, StepDataInterface sdi){
		try {
			commit();
			tx.success();
			tx.close();
			session.close();
			driver.close();
		    super.dispose(smi, sdi);
		}catch(ClientException ce) {
			logError("Error while closing session: " + ce.getMessage());
		}
	}

	
	public void commit() {
		try {
			tx.success();
			tx.close();
			tx = session.beginTransaction();
			logBasic("Committed after " + nbRows + " rows.");
		}catch(Exception e) {
			logError("Error committing after " + nbRows + " rows: " + e.getMessage());
		}
	}
    
	public int createNode(String[] nLabels, String[] nProps, String[] nPropNames) {
    	String[] nodeLabels = nLabels;
    	String[] nodeProps = nProps;
    	String[] nodePropNames = nPropNames;
    	
		// Add labels
		String labels = "n:";
    	for(int i=0; i < nodeLabels.length; i++){
    		String label = escapeLabel(String.valueOf(r[Arrays.asList(fieldNames).indexOf(nodeLabels[i])]));
    		labels += label;
    		if(i != (nodeLabels.length)-1) {
    			labels += ":";
    		}
    	}
		
    	// Add properties
    	Map<String, Object> parameters = new HashMap<String, Object>();
    	String props = " { "; 
    	for(int i=0; i < nodeProps.length; i++){
    		String propName = "";
    		if(!nodePropNames[i].isEmpty()) {
    			propName = nodePropNames[i]; 
    		}else {
    			propName = nodeProps[i]; 
    		}
    		if(r[Arrays.asList(fieldNames).indexOf(nodeProps[i])] instanceof java.lang.Long
    				|| r[Arrays.asList(fieldNames).indexOf(nodeProps[i])] instanceof java.lang.Double 
    				|| r[Arrays.asList(fieldNames).indexOf(nodeProps[i])] instanceof java.math.BigDecimal ){
        		props += propName + " : " + r[Arrays.asList(fieldNames).indexOf(nodeProps[i])];
    		}else{
        		String tmpPropStr = String.valueOf(r[Arrays.asList(fieldNames).indexOf(nodeProps[i])]);
        		props += propName + ": {" + propName + "}";
        		parameters.put(propName, tmpPropStr);
    		}
    		if(i != (nodeProps.length)-1) {
    			props += ", ";
    		}
    		// e.g. { name: 'Andres', title: 'Developer' }
    	}
    	props += "}";

		// CREATE (n:Person:Mens:`Human Being` { name: 'Andres', title: 'Developer' }) return n;
    	String stmt = "MERGE (" + labels + props + ");";
    	
    	try{
    		//StatementResult cursor = tx.run(stmt, parameters);
    		tx.run(stmt, parameters);
    	}catch(Exception e) {
        	logError("Error executing statement: " + stmt);
        	logError(e.getMessage());
    	}
		return 0; 
	}
	
	
    private void createRelationship(){

    	try {
        	String[] fromNodeProps = meta.getFromNodeProps();
        	String[] toNodeProps = meta.getToNodeProps();
        	
        	String[] fromNodePropNames = meta.getFromNodePropNames();
        	String[] toNodePropNames = meta.getToNodePropNames();
        	
        	String[] fNodeLabels = meta.getFromNodeLabels();
        	String[] tNodeLabels = meta.getToNodeLabels();
        	
        	String[] relProps = meta.getRelProps();
        	String[] relPropNames = meta.getRelPropNames(); 
        	
        	String fLabels = "";
        	for(int i=0; i < fNodeLabels.length; i++){
        		String label = escapeProp(String.valueOf(r[Arrays.asList(fieldNames).indexOf(fNodeLabels[i])]));
        		fLabels += label;
        		if(i != (fNodeLabels.length)-1) {
        			fLabels += ":";
        		}
        	}
        	String tLabels = "";
        	for(int i=0; i < tNodeLabels.length; i++){
        		String label = escapeProp(String.valueOf(r[Arrays.asList(fieldNames).indexOf(tNodeLabels[i])]));
        		tLabels += label;
        		if(i != (tNodeLabels.length)-1) {
        			tLabels += ":";
        		}
        	}        	
        	
        	String props = ""; 
        	for(int i=0; i < fromNodeProps.length; i++){
        		String fromPropName = "";
        		String prop = "";
        		if(i == 0) {
        			prop += " WHERE a.";
        		}else {
        			prop += " AND a.";
        		}
        		if(!fromNodePropNames[i].isEmpty()) {
        			prop += fromNodePropNames[i]; 
        		}else {
        			prop += fromNodeProps[i]; 
        		}
        		prop += fromPropName; 
        		
        		String tmpPropStr = String.valueOf(r[Arrays.asList(fieldNames).indexOf(fromNodeProps[i])]); 
        		tmpPropStr = escapeProp(tmpPropStr);        		
        		props += prop + " = " + "\"" + tmpPropStr + "\"";
        	}

        	for(int i=0; i < toNodeProps.length; i++){
        		String toPropName = "";
        		String prop = "";
    			prop += " AND b.";
        		if(!toNodePropNames[i].isEmpty()) {
        			prop += toNodePropNames[i]; 
        		}else {
        			prop += toNodeProps[i]; 
        		}
        		prop += toPropName;
        		
        		String tmpPropStr = String.valueOf(r[Arrays.asList(fieldNames).indexOf(toNodeProps[i])]);
        		tmpPropStr = escapeProp(tmpPropStr); 
        		props += prop + " = " + "\"" + tmpPropStr  + "\"";
        	}

        	
        	// Add properties
        	String relPropStr = ""; 
        	if(relProps.length > 0) {
            	relPropStr = " { "; 
            	for(int i=0; i < relProps.length; i++){
            		String propName = "";
            		if(!relPropNames[i].isEmpty()) {
            			propName = relPropNames[i]; 
            		}else {
            			propName = relProps[i]; 
            		}
            		String tmpPropStr = String.valueOf(r[Arrays.asList(fieldNames).indexOf(relProps[i])]);
            		tmpPropStr = escapeProp(tmpPropStr); 
            		relPropStr += propName + " : " + "\"" + tmpPropStr + "\"";
            		if(i != (relProps.length)-1) {
            			relPropStr += ", ";
            		}
            		// e.g. { name: 'Andres', title: 'Developer' }
            	}
            	relPropStr += "}";
        	}
        	String stmt = "MATCH (a:" + fLabels + "), (b:" + tLabels + ")"
        			+ props
        			//+ " CREATE (a)-[r:`" + String.valueOf(r[Arrays.asList(fieldNames).indexOf(meta.getRelationship())]) + "` " + relPropStr + "] -> (b)"; 
        			+ " MERGE (a)-[r:`" + String.valueOf(r[Arrays.asList(fieldNames).indexOf(meta.getRelationship())]) + "` " + relPropStr + "] -> (b)"; 
        	try{
        		//StatementResult cursor = tx.run(stmt);
        		tx.run(stmt);
        	}catch(Exception e) {
            	logError("Error executing statement: " + stmt);
            	logError(e.getMessage());
        	}
    	}catch(NullPointerException npe) {
    		// catch without throwing an error. 
    	}catch(ArrayIndexOutOfBoundsException oobe) {
    		// catch without throwing an error. 
    	}
    }
    
    
	public String escapeLabel(String str) {
		if(str.contains(" ") || str.contains(".")) {
			str = "`" + str + "`";
		}
		return str; 
	}
    
	public String escapeProp(String str) {
		return StringEscapeUtils.escapeJava(str); 
	}
	
}
