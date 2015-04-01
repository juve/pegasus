import imp
import logging
import getpass
from sqlalchemy import create_engine, orm, event
from sqlalchemy.engine import Engine
from sqlite3 import Connection as SQLite3Connection

from Pegasus import user as users

__all__ = ['connect']

log = logging.getLogger(__name__)

# This turns on foreign keys for SQLite3 connections
@event.listens_for(Engine, "connect")
def _set_sqlite_pragma(conn, record):
    if isinstance(conn, SQLite3Connection):
        log.debug("Turning on foreign keys")
        cursor = conn.cursor()
        cursor.execute("PRAGMA foreign_keys=ON;")
        cursor.close()

def connect_to_master_db(user=None):
    "Connect to 'user's master database"

    if user is None:
        user = getpass.getuser()

    u = users.get_user_by_username(user)

    dburi = u.get_master_db_url()

    return connect(dburi)

def connect(dburi, echo=False, schema_check=True, create=False):
    
    _validate(dburi)
    
    engine = create_engine(dburi, echo=echo, pool_recycle=True)

#    if create:
#        from Pegasus.db.admin.admin_loader import create_database
#        create_database(dburi, engine)
    from Pegasus.db.admin.admin_loader import db_create
    db_create(dburi, engine)

    Session = orm.sessionmaker(bind=engine, autoflush=False, autocommit=False,
                               expire_on_commit=False)

    # TODO Check schema
    if schema_check:
        pass

    return orm.scoped_session(Session)

def _validate(dburi):
    
    try:
        if dburi:
            if dburi.startswith("postgresql:"):
                imp.find_module('psycopg2')
            if dburi.startswith("mysql:"):
                imp.find_module('MySQLdb')
            
    except ImportError, e:
        log.error("Missing Python module: %s" % e)
        raise RuntimeError(e)