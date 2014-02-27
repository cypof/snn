SNN - Streaming Neural Net

Prototype distributed SGD implementation. Local nodes perform Hogwild, and gradients are propagated between machines through UDP streaming.

Weights for each layer are stored in an array, that is chunked at a UDP compatible size. Chunks are assigned a master on the cluster in a round robin way. For each chunk, machines continuously send gradients if they are slave, and broadcast current value I they are master.

Local SGD is written in Java, with prototypes for GPU and runtime-generated C kernels. Streaming and SGD are decoupled, so that the algo always uses 100% of both the CPU and network.

snn/src/main/samples/samples contains a demo Drednet on MNIST with launchers for running locally, on a cluster through ssh, and on EC2.
