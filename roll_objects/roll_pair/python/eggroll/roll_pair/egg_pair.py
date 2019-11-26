# -*- coding: utf-8 -*-
#  Copyright (c) 2019 - now, Eggroll Authors. All Rights Reserved.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import grpc
from concurrent import futures
from eggroll.core.command.command_router import CommandRouter
from eggroll.core.command.command_service import CommandServicer
from eggroll.core.datastructure.broker import FifoBroker
from eggroll.core.io.kv_adapter import RocksdbSortedKvAdapter
from eggroll.core.io.io_utils import get_db_path
from eggroll.core.meta_model import ErTask, ErPartition
from eggroll.core.proto import command_pb2_grpc, transfer_pb2_grpc
from eggroll.core.serdes import cloudpickle
from eggroll.core.transfer.transfer_service import GrpcTransferServicer, \
  TransferClient
from eggroll.roll_pair.shuffler import DefaultShuffler
from grpc._cython import cygrpc

class EggPair(object):
  def run_task(self, task: ErTask):
    functors = task._job._functors
    result = task

    if task._name == 'mapValues':
      f = cloudpickle.loads(functors[0]._body)
      input_partition = task._inputs[0]
      output_partition = task._outputs[0]

      input_adapter = RocksdbSortedKvAdapter(
        options={'path': get_db_path(input_partition)})
      output_adapter = RocksdbSortedKvAdapter(
        options={'path': get_db_path(output_partition)})

      input_iterator = input_adapter.iteritems()
      output_writebatch = output_adapter.new_batch()

      for k_bytes, v_bytes in input_iterator:
        output_writebatch.put(k_bytes, f(v_bytes))

      output_writebatch.close()
      input_adapter.close()
      output_adapter.close()
    elif task._name == 'map':
      f = cloudpickle.loads(functors[0]._body)

      input_partition = task._inputs[0]
      output_partition = task._outputs[0]
      print(output_partition)

      p = output_partition._store_locator._partitioner

      # todo: decide partitioner
      p = lambda k : k[-1] % output_partition._store_locator._total_partitions
      input_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(input_partition)})
      output_store = task._job._outputs[0]

      shuffle_broker = FifoBroker()
      shuffler = DefaultShuffler(task._job._id, shuffle_broker, output_store, output_partition, p)

      for k_bytes, v_bytes in input_adapter.iteritems():
        shuffle_broker.put(f(k_bytes, v_bytes))
      print('finish calculating')
      input_adapter.close()
      shuffle_broker.signal_write_finish()

      shuffler.start()

      shuffle_finished = shuffler.wait_until_finished(600)

      print('map finished')
    # todo: use aggregate to reduce (also reducing duplicate codes)
    elif task._name == 'reduce':
      f = cloudpickle.loads(functors[0]._body)

      input_partition = task._inputs[0]
      input_adapter = RocksdbSortedKvAdapter(
        options={'path': get_db_path(input_partition)})
      seq_op_result = None

      for k_bytes, v_bytes in input_adapter.iteritems():
        if seq_op_result:
          seq_op_result = f(seq_op_result, v_bytes)
        else:
          seq_op_result = v_bytes

      partition_id = input_partition._id
      transfer_tag = task._job._name

      if 0 == partition_id:
        queue = GrpcTransferServicer.get_or_create_broker(transfer_tag)
        partition_size = len(task._job._inputs[0]._partitions)

        comb_op_result = seq_op_result

        for i in range(1, partition_size):
          other_seq_op_result = queue.get(block=True, timeout=10)

          comb_op_result = f(comb_op_result, other_seq_op_result.data)

        print('reduce finished. result: ', comb_op_result)
        output_partition = task._outputs[0]
        output_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(output_partition)})

        output_writebatch = output_adapter.new_batch()
        output_writebatch.put('result'.encode(), comb_op_result)

        output_writebatch.close()
        output_adapter.close()
      else:
        transfer_client = TransferClient()
        transfer_client.send_single(data=seq_op_result, tag=transfer_tag,
                                    processor=task._outputs[0]._processor)

      input_adapter.close()
      print('reduce finished')
    elif task._name == 'aggregate':
      self.aggregate(task)
      print('aggregate finished')
    elif task._name == 'join':
      f = cloudpickle.loads(functors[0]._body)
      left_partition = task._inputs[0]
      right_partition = task._inputs[1]
      output_partition = task._outputs[0]

      left_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(left_partition)})
      right_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(right_partition)})
      output_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(output_partition)})

      left_iterator = left_adapter.iteritems()
      right_iterator = right_adapter.iteritems()
      output_writebatch = output_adapter.new_batch()

      for k_bytes, l_v_bytes in left_iterator:
        r_v_bytes = right_adapter.get(k_bytes)
        if r_v_bytes:
          output_writebatch.put(k_bytes, f(l_v_bytes, r_v_bytes))

      output_writebatch.close()
      left_adapter.close()
      right_adapter.close()
      output_adapter.close()

    return result

  def aggregate(self, task: ErTask):
    functors = task._job._functors
    zero_value = cloudpickle.loads(functors[0]._body)
    seq_op = cloudpickle.loads(functors[1]._body)
    comb_op = cloudpickle.loads(functors[2]._body)

    input_partition = task._inputs[0]
    input_adapter = RocksdbSortedKvAdapter(
        options={'path': get_db_path(input_partition)})

    seq_op_result = zero_value if zero_value is not None else None

    for k_bytes, v_bytes in input_adapter.iteritems():
      if seq_op_result:
        seq_op_result = seq_op(seq_op_result, v_bytes)
      else:
        seq_op_result = v_bytes

    partition_id = input_partition._id
    transfer_tag = task._job._name

    if 0 == partition_id:
      queue = GrpcTransferServicer.get_or_create_broker(transfer_tag)
      partition_size = len(task._job._inputs[0]._partitions)

      comb_op_result = seq_op_result

      for i in range(1, partition_size):
        other_seq_op_result = queue.get(block=True, timeout=10)

        comb_op_result = comb_op(comb_op_result, other_seq_op_result.data)

      print('aggregate finished. result: ', comb_op_result)
      output_partition = task._outputs[0]
      output_adapter = RocksdbSortedKvAdapter(
          options={'path': get_db_path(output_partition)})

      output_writebatch = output_adapter.new_batch()
      output_writebatch.put('result'.encode(), comb_op_result)

      output_writebatch.close()
      output_adapter.close()
    else:
      transfer_client = TransferClient()
      transfer_client.send_single(data=seq_op_result, tag=transfer_tag,
                                  processor=task._outputs[0]._processor)

    input_adapter.close()
    print('reduce finished')


def serve():
  port = 20001
  prefix = 'v1/egg-pair'

  CommandRouter.get_instance().register(
    service_name=f"{prefix}/mapValues",
    route_to_module_name="eggroll.roll_pair.egg_pair",
    route_to_class_name="EggPair",
    route_to_method_name="run_task")
  CommandRouter.get_instance().register(
      service_name=f"{prefix}/map",
      route_to_module_name="eggroll.roll_pair.egg_pair",
      route_to_class_name="EggPair",
      route_to_method_name="run_task")
  CommandRouter.get_instance().register(
      service_name=f"{prefix}/reduce",
      route_to_module_name="eggroll.roll_pair.egg_pair",
      route_to_class_name="EggPair",
      route_to_method_name="run_task")
  CommandRouter.get_instance().register(
      service_name=f"{prefix}/join",
      route_to_module_name="eggroll.roll_pair.egg_pair",
      route_to_class_name="EggPair",
      route_to_method_name="run_task")
  CommandRouter.get_instance().register(
      service_name=f"{prefix}/runTask",
      route_to_module_name="eggroll.roll_pair.egg_pair",
      route_to_class_name="EggPair",
      route_to_method_name="run_task")

  server = grpc.server(futures.ThreadPoolExecutor(max_workers=5),
                       options=[
                         (cygrpc.ChannelArgKey.max_send_message_length, -1),
                         (cygrpc.ChannelArgKey.max_receive_message_length, -1)])

  command_servicer = CommandServicer()
  # todo: register egg_pair methods
  command_pb2_grpc.add_CommandServiceServicer_to_server(command_servicer,
                                                        server)

  transfer_servicer = GrpcTransferServicer()
  transfer_pb2_grpc.add_TransferServiceServicer_to_server(transfer_servicer,
                                                          server)

  server.add_insecure_port(f'[::]:{port}')

  server.start()

  print('server started')
  import time
  time.sleep(10000)


if __name__ == '__main__':
  serve()