package org.opensingular.dbuserprovider.persistence;

import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.mindrot.jbcrypt.BCrypt;
import org.opensingular.dbuserprovider.DBUserStorageException;
import org.opensingular.dbuserprovider.model.QueryConfigurations;
import org.opensingular.dbuserprovider.util.PBKDF2SHA256HashingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil;
import org.opensingular.dbuserprovider.util.PagingUtil.Pageable;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;


@JBossLog
public class UserRepository {


    private DataSourceProvider  dataSourceProvider;
    private QueryConfigurations queryConfigurations;

    public UserRepository(DataSourceProvider dataSourceProvider, QueryConfigurations queryConfigurations) {
        this.dataSourceProvider  = dataSourceProvider;
        this.queryConfigurations = queryConfigurations;
    }


    private <T> T doQuery(String query, Pageable pageable, Function<ResultSet, T> resultTransformer, Object... params) {
        log.infov("Query");
        Optional<DataSource> dataSourceOpt = dataSourceProvider.getDataSource();
        if (dataSourceOpt.isPresent()) {
            DataSource dataSource = dataSourceOpt.get();
            try (Connection c = dataSource.getConnection()) {
                if (pageable != null) {
                    query = PagingUtil.formatScriptWithPageable(query, pageable, queryConfigurations.getRDBMS());
                }
                log.infov("Query: {0} params: {1} ", query, Arrays.toString(params));
                try (PreparedStatement statement = c.prepareStatement(query)) {
                    if (params != null) {
                        for (int i = 1; i <= params.length; i++) {
                            statement.setObject(i, params[i - 1]);
                        }
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        return resultTransformer.apply(rs);
                    }
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return null;
        }
        return null;
    }

    private List<Map<String, String>> readMap(ResultSet rs) {
        log.infov("readMap");

        try {
            List<Map<String, String>> data         = new ArrayList<>();
            Set<String>               columnsFound = new HashSet<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                columnsFound.add(rs.getMetaData().getColumnLabel(i));
            }
            while (rs.next()) {
                Map<String, String> result = new HashMap<>();
                for (String col : columnsFound) {
                    result.put(col,rs.getString(col));
                }
                data.add(result);
            }
            log.infov("data {0}", data);
            return data;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }


    private Integer readInt(ResultSet rs) {
        try {
            return rs.next() ? rs.getInt(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    private Boolean readBoolean(ResultSet rs) {
        try {
            return rs.next() ? rs.getBoolean(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    private String readString(ResultSet rs) {
        try {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new DBUserStorageException(e.getMessage(), e);
        }
    }

    public List<Map<String, String>> getAllUsers() {
        return doQuery(queryConfigurations.getListAll(), null, this::readMap);
    }

    public int getUsersCount(String search) {
        if (search == null || search.isEmpty()) {
            return Optional.ofNullable(doQuery(queryConfigurations.getCount(), null, this::readInt)).orElse(0);
        } else {
            String query = String.format("select count(*) from (%s) count", queryConfigurations.getFindBySearchTerm());
            return Optional.ofNullable(doQuery(query, null, this::readInt, search)).orElse(0);
        }
    }


    public Map<String, String> findUserById(String id) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindById(), null, this::readMap, Integer.valueOf(id)))
                .orElse(Collections.emptyList())
                .stream().findFirst().orElse(null);
    }

    public Optional<Map<String, String>> findUserByUsername(String username) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindByUsername(), null, this::readMap, username))
                .orElse(Collections.emptyList())
                .stream().findFirst();
    }

    public Optional<Map<String, String>> findUserByEmail(String email) {
        return Optional.ofNullable(doQuery(queryConfigurations.getFindByEmail(), null, this::readMap, email))
                .orElse(Collections.emptyList())
                .stream().findFirst();
    }

    public List<Map<String, String>> findUsers(String search, PagingUtil.Pageable pageable) {
        if (search == null || search.isEmpty()) {
            return doQuery(queryConfigurations.getListAll(), pageable, this::readMap);
        }
        return doQuery(queryConfigurations.getFindBySearchTerm(), pageable, this::readMap, "%" + search + "%");
    }

    public boolean validateCredentials(String username, String password) {
        log.infov("validateCredentials: {0}, {1}", username, password);

        String hash = Optional.ofNullable(doQuery(queryConfigurations.getFindPasswordHash(), null, this::readString, username)).orElse("");

        if (queryConfigurations.isBlowfish()) {
            return !hash.isEmpty() && BCrypt.checkpw(password, hash);
        } else {
            String hashFunction = queryConfigurations.getHashFunction();
            log.infov("hashFunction {0}", hashFunction);

            if(hashFunction.equals("PBKDF2-SHA256")){
                String[] components = hash.split("\\$");
                return new PBKDF2SHA256HashingUtil(password, components[2], Integer.valueOf(components[1])).validatePassword(components[3]);
            }

            MessageDigest digest  = DigestUtils.getDigest(hashFunction);

            String s = Hex.encodeHexString(digest.digest(StringUtils.getBytesUtf8(password.toLowerCase())));

            log.infov("Hash from database {0} ", hash);
            log.infov("Calculated {0}", s);

            boolean res = Objects.equals(s, hash);
            log.infov("result of hash comparison: {0}", res);
            return res;
        }
    }

    public boolean updateCredentials(String username, String password) {
        throw new NotImplementedException("Password update not supported");
    }

    public boolean removeUser() {
        return queryConfigurations.getAllowKeycloakDelete();
    }
}
