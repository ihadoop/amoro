/**
 * Autogenerated by Thrift Compiler (0.16.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.amoro.api;


@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.16.0)", date = "2025-01-13")
public enum TaskType implements org.apache.thrift.TEnum {
  SYSTEM(0),
  OPTIMIZE(1);

  private final int value;

  private TaskType(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  @org.apache.amoro.shade.thrift.org.apache.thrift.annotation.Nullable
  public static TaskType findByValue(int value) { 
    switch (value) {
      case 0:
        return SYSTEM;
      case 1:
        return OPTIMIZE;
      default:
        return null;
    }
  }
}
