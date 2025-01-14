package net.sympower.symbok;

import lombok.core.configuration.ConfigurationKey;
import lombok.core.configuration.ConfigurationKeysLoader;
import lombok.core.configuration.FlagUsageType;
import org.kohsuke.MetaInfServices;

@MetaInfServices
public class ConfigurationKeys implements ConfigurationKeysLoader {

  public static final ConfigurationKey<String> READ_WRITE_LOCK_DEFAULT_FIELD_NAME =
      new ConfigurationKey<String>(
          "symbok.readWriteLock.defaultFieldName",
          "Default lock field name for @ReadLock and @WriteLock"
      ) {};

  public static final ConfigurationKey<FlagUsageType> THREAD_NAMED_FLAG_USAGE =
      new ConfigurationKey<FlagUsageType>(
          "symbok.threadNamed.flagUsage",
          "Emit a warning or error if @ThreadNamed is used."
      ) {};

  public static final ConfigurationKey<FlagUsageType> GETTER2_FLAG_USAGE =
      new ConfigurationKey<FlagUsageType>(
          "symbok.getter2.flagUsage",
          "Emit a warning or error if @Getter2 is used."
      ) {};
}
