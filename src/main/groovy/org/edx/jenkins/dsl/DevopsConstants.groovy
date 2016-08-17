package org.edx.jenkins.dsl

class DevopsConstants {

    public static def common_wrappers = {
        timestamps()
        buildUserVars()
        maskPasswords()
    }

     public static def common_logrotator = {
         daysToKeep(7)
     }

}
