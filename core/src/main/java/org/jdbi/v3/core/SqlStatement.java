/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.core;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;

import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.argument.Arguments;
import org.jdbi.v3.core.argument.CharacterStreamArgument;
import org.jdbi.v3.core.argument.InputStreamArgument;
import org.jdbi.v3.core.argument.NamedArgumentFinder;
import org.jdbi.v3.core.argument.NullArgument;
import org.jdbi.v3.core.argument.ObjectArgument;
import org.jdbi.v3.core.exception.UnableToCreateStatementException;
import org.jdbi.v3.core.exception.UnableToExecuteStatementException;
import org.jdbi.v3.core.mapper.RowMappers;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.rewriter.RewrittenStatement;
import org.jdbi.v3.core.statement.SqlStatements;
import org.jdbi.v3.core.statement.StatementBuilder;
import org.jdbi.v3.core.statement.StatementCustomizer;
import org.jdbi.v3.core.statement.StatementCustomizers;
import org.jdbi.v3.core.transaction.TransactionState;
import org.jdbi.v3.core.util.GenericType;
import org.jdbi.v3.core.util.GenericTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides the common functions between <code>Query</code> and
 * <code>Update</code>. It defines most of the argument binding functions
 * used by its subclasses.
 */
public abstract class SqlStatement<This extends SqlStatement<This>> extends BaseStatement<This> {
    private static final Logger LOG = LoggerFactory.getLogger(SqlStatement.class);

    private final This             typedThis;
    private final Binding          params;
    private final Handle           handle;
    private final String           sql;
    private final StatementBuilder statementBuilder;

    /**
     * This will be set on execution, not before
     */
    private       RewrittenStatement rewritten;
    private       PreparedStatement  stmt;

    @SuppressWarnings("unchecked")
    SqlStatement(ConfigRegistry config,
                 Binding params,
                 Handle handle,
                 StatementBuilder statementBuilder,
                 String sql,
                 StatementContext ctx,
                 Collection<StatementCustomizer> statementCustomizers) {
        super(config, ctx);
        assert verifyOurNastyDowncastIsOkay();

        addCustomizers(statementCustomizers);

        this.typedThis = (This) this;
        this.statementBuilder = statementBuilder;
        this.handle = handle;
        this.sql = sql;
        this.params = params;

        ctx.setConnection(handle.getConnection());
        ctx.setRawSql(sql);
        ctx.setBinding(params);
    }

    public This setFetchDirection(final int value)
    {
        addStatementCustomizer(new StatementCustomizers.FetchDirectionStatementCustomizer(value));
        return typedThis;
    }

    /**
     * Provides a means for custom statement modification. Common cusotmizations
     * have their own methods, such as {@link Query#setMaxRows(int)}
     *
     * @param customizer instance to be used to cstomize a statement
     *
     * @return modified statement
     */
    public This addStatementCustomizer(StatementCustomizer customizer)
    {
        super.addCustomizer(customizer);
        return typedThis;
    }

    private boolean verifyOurNastyDowncastIsOkay()
    {
        // Prevent bogus signatures like Update extends SqlStatement<Query>
        // SqlStatement's generic parameter must be supertype of getClass()
        return GenericTypes.findGenericParameter(getClass(), SqlStatement.class)
                .map(GenericTypes::getErasedType)
                .map(type -> type.isAssignableFrom(getClass()))
                .orElse(true); // subclass is raw type.. ¯\_(ツ)_/¯
    }

    protected StatementBuilder getStatementBuilder()
    {
        return statementBuilder;
    }

    protected Binding getParams()
    {
        return params;
    }

    protected Handle getHandle()
    {
        return handle;
    }

    /**
     * @return the un-translated SQL used to create this statement
     */
    protected String getSql()
    {
        return sql;
    }

    /**
     * Set the query timeout, in seconds, on the prepared statement
     *
     * @param seconds number of seconds before timing out
     *
     * @return the same Query instance
     */
    public This setQueryTimeout(final int seconds)
    {
        return addStatementCustomizer(new StatementCustomizers.QueryTimeoutCustomizer(seconds));
    }

    /**
     * Close the handle when the statement is closed.
     *
     * @return the same Query instance
     */
    public This cleanupHandle()
    {
        super.addCleanable(Cleanables.forHandle(handle, TransactionState.ROLLBACK));
        return typedThis;
    }

    /**
     * Force transaction state when the statement is cleaned up.
     *
     * @param state the transaction state to enforce.
     *
     * @return the same Query instance
     */
    public This cleanupHandle(final TransactionState state)
    {
        super.addCleanable(Cleanables.forHandle(handle, state));
        return typedThis;
    }


    /**
     * Used if you need to have some exotic parameter bound.
     *
     * @param position position to bindBinaryStream this argument, starting at 0
     * @param argument exotic argument factory
     *
     * @return the same Query instance
     */
    @SuppressWarnings("unchecked")
    public This bind(int position, Argument argument)
    {
        getParams().addPositional(position, argument);
        return (This) this;
    }

    /**
     * Used if you need to have some exotic parameter bound.
     *
     * @param name     name to bindBinaryStream this argument
     * @param argument exotic argument factory
     *
     * @return the same Query instance
     */
    public This bind(String name, Argument argument)
    {
        getParams().addNamed(name, argument);
        return typedThis;
    }

    /**
     * Binds named parameters from JavaBean properties on the argument.
     *
     * @param bean source of named parameter values to use as arguments
     *
     * @return modified statement
     */
    public This bindBean(Object bean)
    {
        return bindNamedArgumentFinder(new BeanPropertyArguments(null, bean, getContext()));
    }

    /**
     * Binds named parameters from JavaBean properties on the bean argument, with the given prefix.
     *
     * Example: the prefix {@code foo} applied to a bean property {@code bar} will be bound as {@code foo.bar}.
     *
     * @param prefix a prefix to apply to all property names.
     * @param bean source of named parameter values to use as arguments
     *
     * @return modified statement
     */
    public This bindBean(String prefix, Object bean)
    {
        return bindNamedArgumentFinder(new BeanPropertyArguments(prefix, bean, getContext()));
    }

    /**
     * Binds named parameters from a map of String to Object instances
     *
     * @param map map where keys are matched to named parameters in order to bind arguments.
     *            Can be null, in which case the binding has no effect.
     *
     * @return modified statement
     */
    public This bindMap(Map<String, ?> map)
    {
        return map == null ? typedThis : bindNamedArgumentFinder(new MapArguments(map, getContext()));
    }

    /**
     * Binds a new {@link NamedArgumentFinder}.
     *
     * @param namedArgumentFinder A NamedArgumentFinder to bind. Can be null.
     *
     * @return the same Query instance
     */
    public This bindNamedArgumentFinder(final NamedArgumentFinder namedArgumentFinder)
    {
        if (namedArgumentFinder != null) {
            getParams().addNamedArgumentFinder(namedArgumentFinder);
        }

        return typedThis;
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Character value)
    {
        return bind(position, toArgument(Character.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Character value)
    {
        return bind(name, toArgument(Character.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, String value)
    {
        return bind(position, toArgument(String.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, String value)
    {
        return bind(name, toArgument(String.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, int value)
    {
        return bind(position, toArgument(int.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Integer value)
    {
        return bind(position, toArgument(Integer.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, int value)
    {
        return bind(name, toArgument(int.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Integer value)
    {
        return bind(name, toArgument(Integer.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, char value)
    {
        return bind(position, toArgument(char.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, char value)
    {
        return bind(name, toArgument(char.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     * @param length   how long is the stream being bound?
     *
     * @return the same Query instance
     */
    public final This bindASCIIStream(int position, InputStream value, int length)
    {
        return bind(position, new InputStreamArgument(value, length, true));
    }

    /**
     * Bind an argument by name
     *
     * @param name   token name to bind the parameter to
     * @param value  to bind
     * @param length bytes to read from value
     *
     * @return the same Query instance
     */
    public final This bindASCIIStream(String name, InputStream value, int length)
    {
        return bind(name, new InputStreamArgument(value, length, true));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, BigDecimal value)
    {
        return bind(position, toArgument(BigDecimal.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, BigDecimal value)
    {
        return bind(name, toArgument(BigDecimal.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     * @param length the number of bytes in the stream.
     *
     * @return the same Query instance
     */
    public final This bindBinaryStream(int position, InputStream value, int length)
    {
        return bind(position, new InputStreamArgument(value, length, false));
    }

    /**
     * Bind an argument by name
     *
     * @param name   token name to bind the parameter to
     * @param value  to bind
     * @param length bytes to read from value
     *
     * @return the same Query instance
     */
    public final This bindBinaryStream(String name, InputStream value, int length)
    {
        return bind(name, new InputStreamArgument(value, length, false));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Blob value)
    {
        return bind(position, toArgument(Blob.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Blob value)
    {
        return bind(name, toArgument(Blob.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, boolean value)
    {
        return bind(position, toArgument(boolean.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Boolean value)
    {
        return bind(position, toArgument(Boolean.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, boolean value)
    {
        return bind(name, toArgument(boolean.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Boolean value)
    {
        return bind(name, toArgument(Boolean.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, byte value)
    {
        return bind(position, toArgument(byte.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Byte value)
    {
        return bind(position, toArgument(Byte.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, byte value)
    {
        return bind(name, toArgument(byte.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Byte value)
    {
        return bind(name, toArgument(Byte.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, byte[] value)
    {
        return bind(position, toArgument(byte[].class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, byte[] value)
    {
        return bind(name, toArgument(byte[].class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     * @param length   number of characters to read
     *
     * @return the same Query instance
     */
    public final This bind(int position, Reader value, int length)
    {

        return bind(position, new CharacterStreamArgument(value, length));
    }

    /**
     * Bind an argument by name
     *
     * @param name   token name to bind the parameter to
     * @param value  to bind
     * @param length number of characters to read
     *
     * @return the same Query instance
     */
    public final This bind(String name, Reader value, int length)
    {
        return bind(name, new CharacterStreamArgument(value, length));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Clob value)
    {
        return bind(position, toArgument(Clob.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Clob value)
    {
        return bind(name, toArgument(Clob.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, java.sql.Date value)
    {
        return bind(position, toArgument(java.sql.Date.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, java.sql.Date value)
    {
        return bind(name, toArgument(java.sql.Date.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, java.util.Date value)
    {
        return bind(position, toArgument(java.util.Date.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, java.util.Date value)
    {
        return bind(name, toArgument(java.util.Date.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, double value)
    {
        return bind(position, toArgument(double.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Double value)
    {
        return bind(position, toArgument(Double.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, double value)
    {
        return bind(name, toArgument(double.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Double value)
    {
        return bind(name, toArgument(Double.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, float value)
    {
        return bind(position, toArgument(float.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Float value)
    {
        return bind(position, toArgument(Float.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, float value)
    {
        return bind(name, toArgument(float.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Float value)
    {
        return bind(name, toArgument(Float.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, long value)
    {
        return bind(position, toArgument(long.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Long value)
    {
        return bind(position, toArgument(Long.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, long value)
    {
        return bind(name, toArgument(long.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Long value)
    {
        return bind(name, toArgument(Long.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Short value)
    {
        return bind(position, toArgument(Short.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, short value)
    {
        return bind(position, toArgument(short.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, short value)
    {
        return bind(name, toArgument(short.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Short value)
    {
        return bind(name, toArgument(short.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Object value)
    {
        return bind(position, toArgument(value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Object value)
    {
        return bind(name, toArgument(value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Time value)
    {
        return bind(position, toArgument(Time.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Time value)
    {
        return bind(name, toArgument(Time.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, Timestamp value)
    {
        return bind(position, toArgument(Timestamp.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, Timestamp value)
    {
        return bind(name, toArgument(Timestamp.class, value));
    }

    /**
     * Bind an argument positionally
     *
     * @param position position to bind the parameter at, starting at 0
     * @param value    to bind
     *
     * @return the same Query instance
     */
    public final This bind(int position, URL value)
    {
        return bind(position, toArgument(URL.class, value));
    }

    /**
     * Bind an argument by name
     *
     * @param name  token name to bind the parameter to
     * @param value to bind
     *
     * @return the same Query instance
     */
    public final This bind(String name, URL value)
    {
        return bind(name, toArgument(URL.class, value));
    }

    /**
     * Bind an argument dynamically by the type passed in.
     *
     * @param position     position to bind the parameter at, starting at 0
     * @param value        to bind
     * @param argumentType type for value argument
     *
     * @return the same Query instance
     */
    public final This bindByType(int position, Object value, Type argumentType)
    {
        return bind(position, toArgument(argumentType, value));
    }

    /**
     * Bind an argument dynamically by the generic type passed in.
     *
     * @param position     position to bind the parameter at, starting at 0
     * @param value        to bind
     * @param argumentType type token for value argument
     *
     * @return the same Query instance
     */
    public final This bindByType(int position, Object value, GenericType<?> argumentType)
    {
        return bindByType(position, value, argumentType.getType());
    }

    /**
     * Bind an argument dynamically by the type passed in.
     *
     * @param name         token name to bind the parameter to
     * @param value        to bind
     * @param argumentType type for value argument
     *
     * @return the same Query instance
     */
    public final This bindByType(String name, Object value, Type argumentType)
    {
        return bind(name, toArgument(argumentType, value));
    }

    /**
     * Bind an argument dynamically by the generic type passed in.
     *
     * @param name         token name to bind the parameter to
     * @param value        to bind
     * @param argumentType type token for value argument
     *
     * @return the same Query instance
     */
    public final This bindByType(String name, Object value, GenericType<?> argumentType)
    {
        return bindByType(name, value, argumentType.getType());
    }

    private Argument toArgument(Object value) {
        return toArgument(value == null ? Object.class : value.getClass(), value);
    }

    private Argument toArgument(Type type, Object value) {
        return getConfig(Arguments.class)
                .findFor(type, value, getConfig())
                .orElseThrow(() -> new UnsupportedOperationException("No argument factory registered for '" + value + "' of type " + type));
    }

    /**
     * Bind NULL to be set for a given argument.
     *
     * @param name    Named parameter to bind to
     * @param sqlType The sqlType must be set and is a value from <code>java.sql.Types</code>
     *
     * @return the same statement instance
     */
    public final This bindNull(String name, int sqlType)
    {
        return bind(name, new NullArgument(sqlType));
    }

    /**
     * Bind NULL to be set for a given argument.
     *
     * @param position position to bind NULL to, starting at 0
     * @param sqlType  The sqlType must be set and is a value from <code>java.sql.Types</code>
     *
     * @return the same statement instance
     */
    public final This bindNull(int position, int sqlType)
    {
        return bind(position, new NullArgument(sqlType));
    }

    /**
     * Bind a value using a specific type from <code>java.sql.Types</code> via
     * PreparedStatement#setObject(int, Object, int)
     *
     * @param name    Named parameter to bind at
     * @param value   Value to bind
     * @param sqlType The sqlType from java.sql.Types
     *
     * @return self
     */
    public final This bindBySqlType(String name, Object value, int sqlType)
    {
        return bind(name, new ObjectArgument(value, sqlType));
    }

    /**
     * Bind a value using a specific type from <code>java.sql.Types</code> via
     * PreparedStatement#setObject(int, Object, int)
     *
     * @param position position to bind NULL to, starting at 0
     * @param value    Value to bind
     * @param sqlType  The sqlType from java.sql.Types
     *
     * @return self
     */
    public final This bindBySqlType(int position, Object value, int sqlType)
    {
        return bind(position, new ObjectArgument(value, sqlType));
    }

    PreparedStatement internalExecute()
    {
        rewritten = getConfig(SqlStatements.class)
                .getStatementRewriter()
                .rewrite(sql, getParams(), getContext());
        getContext().setRewrittenSql(rewritten.getSql());
        try {
            if (getClass().isAssignableFrom(Call.class)) {
                stmt = statementBuilder.createCall(handle.getConnection(), rewritten.getSql(), getContext());
            }
            else {
                stmt = statementBuilder.create(handle.getConnection(), rewritten.getSql(), getContext());
            }
        }
        catch (SQLException e) {
            throw new UnableToCreateStatementException(e, getContext());
        }

        // The statement builder might (or might not) clean up the statement when called. E.g. the
        // caching statement builder relies on the statement *not* being closed.
        addCleanable(Cleanables.forStatementBuilder(statementBuilder, handle.getConnection(), sql, stmt));

        getContext().setStatement(stmt);

        try {
            rewritten.bind(getParams(), stmt);
        }
        catch (SQLException e) {
            throw new UnableToExecuteStatementException("Unable to bind parameters to query", e, getContext());
        }

        beforeExecution(stmt);

        try {
            final long start = System.nanoTime();
            stmt.execute();
            final long elapsedTime = System.nanoTime() - start;
            LOG.trace("Execute SQL \"{}\" in {}ms", rewritten.getSql(), elapsedTime / 1000000L);
            getConfig(SqlStatements.class)
                    .getTimingCollector()
                    .collect(elapsedTime, getContext());
        }
        catch (SQLException e) {
            try {
                stmt.close();
            } catch (SQLException e1) {
                e.addSuppressed(e1);
            }
            throw new UnableToExecuteStatementException(e, getContext());
        }

        afterExecution(stmt);

        return stmt;
    }

    @SuppressWarnings("unchecked")
    <T> RowMapper<T> rowMapperForType(Class<T> type)
    {
        return (RowMapper<T>) rowMapperForType((Type) type);
    }

    @SuppressWarnings("unchecked")
    <T> RowMapper<T> rowMapperForType(GenericType<T> type)
    {
        return (RowMapper<T>) rowMapperForType(type.getType());
    }

    RowMapper<?> rowMapperForType(Type type)
    {
        return getConfig(RowMappers.class).findFor(type, getConfig())
            .orElseThrow(() -> new UnsupportedOperationException("No mapper registered for " + type));
    }
}
