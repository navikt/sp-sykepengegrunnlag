DO
$$
    BEGIN
        IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sp-sykepengegrunnlag-opprydding-dev')
        THEN
            GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "sp-sykepengegrunnlag-opprydding-dev";
            GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "sp-sykepengegrunnlag-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-sykepengegrunnlag" IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO "sp-sykepengegrunnlag-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-sykepengegrunnlag" IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "sp-sykepengegrunnlag-opprydding-dev";
        END IF;
    END
$$;
