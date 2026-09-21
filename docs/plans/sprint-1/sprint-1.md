# Sprint 1

## sprint 1 goals

- finish bus bunching detection feature

## things we are doing

- **WQAP-2: Automate bus bunching detection**
    
    create a service that polls data for each bus stop, filter out noise (no bus obvious signs of bus bunching) and alerts the the detection if something is found (<90s gap). 
    
    `services/bus-bunching-detection`
    
- **WQAP-3: Root Cause Diagnosis**
    
    create AI service that ingests this detection signal, along with incident information, bus load, and other relevant information, identify whats the issue, and give the OCC/controller a summary of whats happening/causing the bus bunching, and (is this for WQAP-4?) what the controller can do.
    
    Question:
    
    - should this be its own service?
    
    `services/<whats-an-appropriate-name-for-this>`
    
- **WQAP-4: Mitigation Action Recommendations**
    
    create AI service(the recommendation engine) that recommends mitigation actions, displays trade-off, display expected calculated passenger-minute savings
    
    `services/recommendation-engine`
    
- **WQAP-77: Create database schema and logging mechanism for retrospective data.**
    
    create database schema and set up logging mechanism.
    
    We can have 1 PostgreSQL DB that has a separate schema for each service.
    Question:
    
    - does logging need a new service?
- **WQAP-85: Data polling loop from API to database**
    
    create service that continuously polls LTA Datamall at defined interval.
    
    `servicess/data-poller` or some shi


### implementation plan
- do what u (and AI) need to do
- each service is separated and isolated into each folder in services/service-name, so 1 broken service shouldn't break the whole app.
- have fun 😂😂
