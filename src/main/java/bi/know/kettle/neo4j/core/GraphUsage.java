package bi.know.kettle.neo4j.core;

public enum GraphUsage {
  NODE_CREATE, NODE_UPDATE, NODE_DELETE, NODE_READ,
  RELATIONSHIP_CREATE, RELATIONSHIP_UPDATE, RELATIONSHIP_DELETE, RELATIONSHIP_READ
  ;
}
