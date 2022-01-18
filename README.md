# Deploy
Plugin deploy script for Project Eden developers

## Setup Run Configuration in IntelliJ
1. Edit configurations
2. Add JAR Application configuration
3. Name: deploy \<server>
4. Path to JAR: /path/to/Deploy/target/Deploy-1.0.0.jar
5. Program arguments:\
    --ssh-user=\<username>\
    --mc-user=\<username>\
    --workspace=\/path/to/workspace\
    --server=\<server>\
    --plugin=\<plugin>\
    --framework=\<framework>
6. Modify arguments as needed (see man page)
7. Save and run