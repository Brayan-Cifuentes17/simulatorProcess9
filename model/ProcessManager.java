package model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ProcessManager {
    private ArrayList<Process> initialProcesses;
    private ArrayList<Partition> partitions;
    private ArrayList<Log> executionLogs;
    private ArrayList<Partition> internalPartitions; 
    private ArrayList<Condensation> condensations;   

    public ProcessManager() {
        initialProcesses = new ArrayList<>();
        partitions = new ArrayList<>();
        executionLogs = new ArrayList<>();
        
        internalPartitions = new ArrayList<>();
        condensations = new ArrayList<>();
    }



    
    public void addPartition(String name, long size) {
        Partition partition = new Partition(name, size);
        partitions.add(partition);
    }

    public void editPartition(String partitionName, long newSize) {
        Partition partition = findPartitionByName(partitionName);
        if (partition != null) {
            partition.setSize(newSize);
        }
    }

    public boolean partitionExists(String name) {
        return partitions.stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(name.trim()));
    }

    public void removePartition(String name) {
        partitions.removeIf(p -> p.getName().equalsIgnoreCase(name.trim()));
    }

    public Partition findPartitionByName(String name) {
        return partitions.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    public boolean hasPartitionAssignedProcesses(String partitionName) {
        return initialProcesses.stream()
                .anyMatch(p -> p.getPartition() != null && 
                         p.getPartition().getName().equalsIgnoreCase(partitionName));
    }

    public ArrayList<Partition> getPartitions() {
        return new ArrayList<>(partitions);
    }

    // ========== GESTIÓN DE PROCESOS ==========
    
    public void addProcess(String name, long time, Status status, long size) {
        Process process = new Process(name, time, status, size);  
        initialProcesses.add(process);
    }

    public boolean processExists(String name) {
        return initialProcesses.stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(name.trim()));
    }

    public void removeProcess(String name) {
        Process process = findProcessByName(name);
        if (process != null && process.getPartition() != null) {
            process.getPartition().removeProcess(process);
        }
        initialProcesses.removeIf(p -> p.getName().equalsIgnoreCase(name.trim()));
    }

    public void editProcess(int position, String processName, long newTime, 
                        Status newStatus, long newSize) {
        if (position >= 0 && position < initialProcesses.size()) {
            Process existingProcess = initialProcesses.get(position);
            if (existingProcess.getName().equalsIgnoreCase(processName)) {
                existingProcess.setOriginalTime(newTime);
                existingProcess.setStatus(newStatus);
                existingProcess.setSize(newSize);
            }
        }
    }

    private Process findProcessByName(String name) {
        return initialProcesses.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    public boolean isEmpty() {
        return initialProcesses.isEmpty();
    }

    public ArrayList<Process> getInitialProcesses() {
        return new ArrayList<>(initialProcesses);
    }

    // ========== SIMULACIÓN CON CONDENSACIÓN ==========
    
    public void runSimulation() {
        executionLogs.clear();
        condensations.clear();
        
        // Limpiar particiones
        for (Partition p : partitions) {
            p.clearExecutionData();
            p.setAvailable(false);
        }
        
        // ← ASIGNACIÓN AUTOMÁTICA 1:1
        assignPartitionsToProcesses();
        
        // Copiar particiones para trabajar
        internalPartitions = new ArrayList<>(partitions);
        
        // Registrar procesos iniciales
        for (Process p : initialProcesses) {
            addLog(p, Filter.INICIAL);
        }
        
        // Registrar particiones
        for (Partition part : partitions) {
            Process dummyProcess = new Process("", 0, Status.NO_BLOQUEADO, part.getSize());
            dummyProcess.setPartition(part);
            addLog(dummyProcess, Filter.PARTICIONES);
        }
        
        // Ordenar procesos por tiempo (MENOR A MAYOR)
        ArrayList<Process> processQueue = new ArrayList<>();
        for (Process p : initialProcesses) {
            processQueue.add(p.clone());
        }
        
       //processQueue.sort((p1, p2) -> Long.compare(p1.getOriginalTime(), p2.getOriginalTime()));
        
        // Ejecutar simulación
        executeSimulation(processQueue);
    }

    // ← NUEVO: Asignar particiones 1:1
    private void assignPartitionsToProcesses() {
        for (Process process : initialProcesses) {
            // Crear partición del tamaño exacto del proceso
            Partition partition = new Partition("Part" + (partitions.size() + 1), process.getSize());
            partition.addProcess(process);
            partition.setAvailable(false);  // Ocupada inicialmente
            partitions.add(partition);
            
            process.setPartition(partition);
            process.addToPartitionHistory(partition);
        }
    }

    // ← NUEVO: Ejecutar simulación con Round Robin
    private void executeSimulation(ArrayList<Process> processQueue) {
        while (!processQueue.isEmpty()) {
            Process currentProcess = processQueue.remove(0);
            
            // Estados: Listo → Despachar → En Ejecución
            addLog(currentProcess, Filter.LISTO);
            addLog(currentProcess, Filter.DESPACHAR);
            addLog(currentProcess, Filter.EN_EJECUCION);
            
            // Calcular tiempo a ejecutar
            long timeToExecute = Math.min(Constants.QUANTUM_TIME, currentProcess.getRemainingTime());

            // Registrar tiempo en la partición (si tiene)
            if (currentProcess.getPartition() != null) {
                currentProcess.getPartition().addExecutionTime(
                    currentProcess.getName(),
                    timeToExecute
                );
            }

            // Restar el tiempo realmente ejecutado
            currentProcess.subtractTime(timeToExecute);
            currentProcess.incrementCycle();
            
            // ¿Terminó?
            if (currentProcess.isFinished()) {
                addLog(currentProcess, Filter.FINALIZADO);
                
                // LIBERAR PARTICIÓN Y BUSCAR CONDENSACIÓN
                int partitionIndex = findPartitionIndex(currentProcess.getPartition());
                if (partitionIndex != -1) {
                    internalPartitions.get(partitionIndex).setAvailable(true);
                    reviewForCondensation(partitionIndex);
                }
                
                continue;
            }
            
            // No terminó: intentar mover a otra partición
            int oldPartitionIndex = findPartitionIndex(currentProcess.getPartition());

            // Liberar la partición actual (crear hueco) antes de buscar una nueva
            if (oldPartitionIndex != -1) {
                Partition old = internalPartitions.get(oldPartitionIndex);
                old.removeProcess(currentProcess);
                old.setAvailable(true);
                
                // ← NUEVO: Revisar condensación INMEDIATAMENTE después de liberar
                reviewForCondensation(oldPartitionIndex);
                
                // ← NUEVO: Actualizar el índice por si cambió después de la condensación
                oldPartitionIndex = findPartitionIndex(old);
            }

            // Buscar mejor ajuste (best-fit) donde quepa
            int fitIndex = findFirstFitIndex(currentProcess);

            if (fitIndex != -1) {
                // Asignar en mejor ajuste (best-fit)
                allocateProcessToPartitionAtIndex(currentProcess, fitIndex);
                currentProcess.setStatus(Status.NO_BLOQUEADO);
                addLog(currentProcess, Filter.TIEMPO_EXPIRADO);
            } else {
                // No hay hueco disponible
                currentProcess.setStatus(Status.BLOQUEADO);
                addLog(currentProcess, Filter.TRANSICION_BLOQUEO);
                addLog(currentProcess, Filter.BLOQUEADO);
            }

            // Volver a encolar para siguiente ciclo
            processQueue.add(currentProcess);
        }
    }

    // Buscar índice de partición libre más pequeña donde quepa el proceso (best-fit)
    private int findFirstFitIndex(Process process) {
        if (process == null) return -1;
        
        int bestIndex = -1;
        long smallestSize = Long.MAX_VALUE;
        
        System.out.println("=== Buscando Best-Fit para " + process.getName() + " (tamaño " + process.getSize() + ") ===");
        
        for (int i = 0; i < internalPartitions.size(); i++) {
            Partition part = internalPartitions.get(i);
            
            System.out.println("  Evaluando " + part.getName() + 
                            " - Tamaño: " + part.getSize() + 
                            " - Disponible: " + part.isAvailable());
            
            if (part.isAvailable() && part.getSize() >= process.getSize()) {
                if (part.getSize() < smallestSize) {
                    smallestSize = part.getSize();
                    bestIndex = i;
                    System.out.println("    Nuevo mejor ajuste: " + part.getName());
                }
            }
        }
        
        if (bestIndex != -1) {
            System.out.println("  → Seleccionado: " + internalPartitions.get(bestIndex).getName());
        } else {
            System.out.println("  → No se encontró partición disponible");
        }
        
        return bestIndex;
    }

    // Asignar proceso a la partición en el índice dado. Si la partición es mayor se crea un hueco (split).
    private void allocateProcessToPartitionAtIndex(Process process, int index) {
        if (process == null || index < 0 || index >= internalPartitions.size()) return;

        Partition target = internalPartitions.get(index);

        // Exact fit
        if (target.getSize() == process.getSize()) {
            target.setAvailable(false);
            target.addProcess(process);
            process.setPartition(target);
            process.addToPartitionHistory(target);
            return;
        }

        // Si la partición es mayor, la dividimos en: partición asignada + hueco
        if (target.getSize() > process.getSize()) {
            
            String allocatedName = "Part" + (partitions.size() + 1);
            Partition allocated = new Partition(allocatedName, process.getSize());
            allocated.setAvailable(false);
            allocated.addProcess(process);
            process.setPartition(allocated);
            process.addToPartitionHistory(allocated);

            long leftover = target.getSize() - process.getSize();
            String holeName = "Part" + (partitions.size() + 2);
            Partition hole = new Partition(holeName, leftover);
            hole.setAvailable(true);

            // Reemplazar en la lista interna: colocar allocated en index y hole en index+1
            internalPartitions.set(index, allocated);
            internalPartitions.add(index + 1, hole);

            // Mantener registro maestro de particiones (coherente con condensaciones previas)
            partitions.add(allocated);
            partitions.add(hole);
            System.out.println("\n DIVISIÓN EJECUTADA:");
            System.out.println("   " + target.getName() + "(" + target.getSize() + ") dividida en:");
            System.out.println("   - " + allocatedName + "(" + process.getSize() + ") asignada a " + process.getName());
            System.out.println("   - " + holeName + "(" + leftover + ") hueco libre");
        }
    }

    //Buscar índice de partición en la lista interna
    private int findPartitionIndex(Partition partition) {
        if (partition == null) return -1;
        
        for (int i = 0; i < internalPartitions.size(); i++) {
            if (internalPartitions.get(i).getName().equals(partition.getName())) {
                return i;
            }
        }
        return -1;
    }

    // ← NUEVO: Revisar y ejecutar condensación
    private void reviewForCondensation(int position) {
        boolean upAvailable = false;
        boolean downAvailable = false;
        
        System.out.println("\n Revisando condensación en posición " + position);
        System.out.println("   Partición actual: " + internalPartitions.get(position).getName());
        
        // ¿Partición de arriba está libre?
        if (position >= 1 && internalPartitions.get(position - 1).isAvailable()) {
            upAvailable = true;
            System.out.println("    Partición arriba libre: " + internalPartitions.get(position - 1).getName());
        }
        
        // ¿Partición de abajo está libre?
        if (position < internalPartitions.size() - 1 && 
            internalPartitions.get(position + 1).isAvailable()) {
            downAvailable = true;
            System.out.println("    Partición abajo libre: " + internalPartitions.get(position + 1).getName());
        }
        
        // Si no hay particiones adyacentes libres, no hacer nada
        if (!upAvailable && !downAvailable) {
            System.out.println("    No hay particiones adyacentes libres");
            return;
        }
        
        Partition newPartition;
        Condensation condensation;
        String condensationName = "Cond" + (condensations.size() + 1);
        String newPartitionName = "Part" + (partitions.size() + 1);
        
        if (upAvailable && downAvailable) {
            System.out.println("   CONDENSACIÓN 3: " + 
                internalPartitions.get(position - 1).getName() + " + " +
                internalPartitions.get(position).getName() + " + " +
                internalPartitions.get(position + 1).getName() + 
                " = " + newPartitionName);
                
            condensation = new Condensation(
                condensationName,
                internalPartitions.get(position - 1),
                internalPartitions.get(position),
                internalPartitions.get(position + 1)
            );
            
            newPartition = new Partition(newPartitionName, condensation.getSize());
            newPartition.setAvailable(true);
            
            partitions.add(newPartition);
            condensations.add(condensation);
            
            internalPartitions.remove(position + 1);
            internalPartitions.remove(position);
            internalPartitions.set(position - 1, newPartition);
            
        } else if (upAvailable) {
            System.out.println("    CONDENSACIÓN 2: " + 
                internalPartitions.get(position - 1).getName() + " + " +
                internalPartitions.get(position).getName() + 
                " = " + newPartitionName);
                
            condensation = new Condensation(
                condensationName,
                internalPartitions.get(position - 1),
                internalPartitions.get(position)
            );
            
            newPartition = new Partition(newPartitionName, condensation.getSize());
            newPartition.setAvailable(true);
            
            partitions.add(newPartition);
            condensations.add(condensation);
            
            internalPartitions.set(position - 1, newPartition);
            internalPartitions.remove(position);
            
        } else if (downAvailable) {
            System.out.println("    CONDENSACIÓN 2: " + 
                internalPartitions.get(position).getName() + " + " +
                internalPartitions.get(position + 1).getName() + 
                " = " + newPartitionName);
                
            condensation = new Condensation(
                condensationName,
                internalPartitions.get(position),
                internalPartitions.get(position + 1)
            );
            
            newPartition = new Partition(newPartitionName, condensation.getSize());
            newPartition.setAvailable(true);
            
            partitions.add(newPartition);
            condensations.add(condensation);
            
            internalPartitions.set(position, newPartition);
            internalPartitions.remove(position + 1);
        }
        
        System.out.println("   Estado memoria después: " + internalPartitions.stream()
            .map(p -> p.getName() + "(" + p.getSize() + "," + (p.isAvailable() ? "L" : "O") + ")")
            .reduce((a, b) -> a + " " + b).orElse("vacío"));
    }

    // ← NUEVO: Obtener condensaciones
    public ArrayList<Condensation> getCondensations() {
        return new ArrayList<>(condensations);
    }

    // ========== LOGS ==========
    
    private void addLog(Process process, Filter filter) {
        Log log = new Log(process, filter);
        executionLogs.add(log);
    }

    public List<Log> getLogsByFilter(Filter filter) {
        return executionLogs.stream()
                .filter(log -> log.getFilter() == filter)
                .collect(Collectors.toList());
    }

    public List<Log> getLogsByFilterAndPartition(Filter filter, String partitionName) {
        return executionLogs.stream()
                .filter(log -> log.getFilter() == filter && 
                       log.getPartition() != null &&
                       log.getPartition().getName().equalsIgnoreCase(partitionName))
                .collect(Collectors.toList());
    }

    public ArrayList<Log> getAllLogs() {
        return new ArrayList<>(executionLogs);
    }

    // ========== INFORMES ==========
    
    public List<PartitionFinalizationInfo> getPartitionFinalizationReport() {
        List<PartitionFinalizationInfo> report = new ArrayList<>();
        
        for (Partition partition : partitions) {
            String processNames = partition.getProcessHistoryString();
            long totalTime = partition.getTotalExecutionTime();
            
            PartitionFinalizationInfo info = new PartitionFinalizationInfo(
                partition.getName(),
                partition.getSize(),
                processNames,
                totalTime
            );
            report.add(info);
        }
        
        report.sort((p1, p2) -> Long.compare(p1.getTotalTime(), p2.getTotalTime()));
        
        return report;
    }

    public static class PartitionFinalizationInfo {
        private String name;
        private long size;
        private String processNames;
        private long totalTime;

        public PartitionFinalizationInfo(String name, long size, String processNames, long totalTime) {
            this.name = name;
            this.size = size;
            this.processNames = processNames;
            this.totalTime = totalTime;
        }

        public String getName() { return name; }
        public long getSize() { return size; }
        public String getProcessNames() { return processNames; }
        public long getTotalTime() { return totalTime; }
    }

   
    public void clearAll() {
        initialProcesses.clear();
        
        for (Partition p : partitions) {
            p.clearExecutionData();
        }
        
        partitions.clear();
        executionLogs.clear();
        internalPartitions.clear();
        condensations.clear();
    }

    public void clearLogs() {
        executionLogs.clear();
    }
}