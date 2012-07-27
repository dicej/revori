package com.readytalk.revori;

public interface TableBuilder {
  
  /**
   * Prepares a RowBuilder to insert or update a row given by the specified 
   * primary key.
   * @return said row builder
   */
  public RowBuilder row(Object ... key);

  /**
   * Deletes the row with the specified primary key.
   * @return self
   */
  public TableBuilder delete(Object ... key);

  /**
   * Indicate that no further updates will
   * be performed on this TableBuilder.
   * @return the parent RevisionBuilder
   */
  public RevisionBuilder up();

  /**
   * Inserts the row with specified primary key,
   * and no other columns.
   * @return self
   */
  public TableBuilder key(Object... key);
}