// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence;

import google.registry.util.TypeUtils.TypeInstantiator;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Generic Hibernate user type to store/retrieve Java collection as an array in Cloud SQL.
 *
 * @param <T> the concrete {@link Collection} type of the entity field
 * @param <E> the Java type of the element for the collection of the entity field
 * @param <C> the JDBC supported type of the element in the DB column array
 */
public abstract class GenericCollectionUserType<T extends Collection<E>, E, C>
    extends MutableUserType {

  abstract T getNewCollection();

  abstract ArrayColumnType getColumnType();

  enum ArrayColumnType {
    STRING(Types.ARRAY, "text");

    final int typeCode;
    final String typeName;

    ArrayColumnType(int typeCode, String typeName) {
      this.typeCode = typeCode;
      this.typeName = typeName;
    }

    int getTypeCode() {
      return typeCode;
    }

    String getTypeName() {
      return typeName;
    }

    String getTypeDdlName() {
      return typeName + "[]";
    }
  }

  @Override
  public Class returnedClass() {
    return new TypeInstantiator<T>(getClass()) {}.getExactType();
  }

  @Override
  public int[] sqlTypes() {
    return new int[] {getColumnType().getTypeCode()};
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    if (rs.getArray(names[0]) != null) {
      T result = getNewCollection();
      for (C element : (C[]) rs.getArray(names[0]).getArray()) {
        result.add(convertToElem(element));
      }
      return result;
    }
    return null;
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    if (value == null) {
      st.setArray(index, null);
      return;
    }
    T collection = (T) value;
    Array arr =
        st.getConnection()
            .createArrayOf(
                getColumnType().getTypeName(),
                collection.stream().map(this::convertToColumn).toArray());
    st.setArray(index, arr);
  }

  /**
   * Override this to convert an element value retrieved from the database to a different type.
   *
   * <p>This method is useful when encoding a java type to one of the types that can be used as an
   * array element.
   */
  protected E convertToElem(C columnValue) {
    return (E) columnValue;
  }

  protected C convertToColumn(E elementValue) {
    return (C) elementValue;
  }
}
