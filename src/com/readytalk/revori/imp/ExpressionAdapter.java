package com.readytalk.revori.imp;

interface ExpressionAdapter {
  public void visit(ExpressionAdapterVisitor visitor);
  public Object evaluate(boolean convertDummyToNull);
  public Scan makeScan(ColumnReferenceAdapter reference);
  public Class type();
}